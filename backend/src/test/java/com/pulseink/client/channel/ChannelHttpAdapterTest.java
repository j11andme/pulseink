package com.pulseink.client.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.publishing.ChannelPort.PublishRequest;
import com.pulseink.service.publishing.ChannelRejectedException;
import com.pulseink.service.publishing.ChannelUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ChannelHttpAdapterTest {

    private static final String SENSITIVE_MARKER = "SENSITIVE-BODY-7f31a9c2";

    private HttpServer server;
    private ChannelHttpAdapter adapter;
    private final List<String> receivedIdempotencyKeys = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void created201MapsToReceipt() throws Exception {
        var key = UUID.randomUUID();
        var externalPostId = UUID.randomUUID();
        startServer(exchange -> respond(exchange, 201, """
                {"externalPostId":"%s","idempotencyKey":"%s","channel":"BLOG",
                 "publishedAt":"2026-08-13T12:00:00Z","replayed":false}
                """.formatted(externalPostId, key)));
        adapter = adapter(Duration.ofSeconds(5), Duration.ofSeconds(5));

        var receipt = adapter.publish(request(key));

        assertThat(receipt.externalPostId()).isEqualTo(externalPostId);
        assertThat(receipt.idempotencyKey()).isEqualTo(key);
        assertThat(receipt.channel()).isEqualTo(CampaignChannel.BLOG);
        assertThat(receipt.publishedAt()).isEqualTo(Instant.parse("2026-08-13T12:00:00Z"));
        assertThat(receipt.replayed()).isFalse();
    }

    @Test
    void replayed200MapsToReceipt() throws Exception {
        var key = UUID.randomUUID();
        var externalPostId = UUID.randomUUID();
        startServer(exchange -> respond(exchange, 200, """
                {"externalPostId":"%s","idempotencyKey":"%s","channel":"SOCIAL",
                 "publishedAt":"2026-08-13T12:00:00Z","replayed":true}
                """.formatted(externalPostId, key)));
        adapter = adapter(Duration.ofSeconds(5), Duration.ofSeconds(5));

        var receipt = adapter.publish(request(key));

        assertThat(receipt.externalPostId()).isEqualTo(externalPostId);
        assertThat(receipt.channel()).isEqualTo(CampaignChannel.SOCIAL);
        assertThat(receipt.replayed()).isTrue();
    }

    @Test
    void readTimeoutFallsBackToLookupByKey() throws Exception {
        var key = UUID.randomUUID();
        var externalPostId = UUID.randomUUID();
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/channel-api/v1/posts")
                    && exchange.getRequestMethod().equals("POST")) {
                respondWithStall(exchange, 201, """
                        {"externalPostId":"%s","idempotencyKey":"%s","channel":"BLOG",
                         "publishedAt":"2026-08-13T12:00:00Z","replayed":false}
                        """.formatted(externalPostId, key), 2_000);
            } else {
                respond(exchange, 200, """
                        {"externalPostId":"%s","idempotencyKey":"%s","channel":"BLOG",
                         "publishedAt":"2026-08-13T12:00:00Z","replayed":true}
                        """.formatted(externalPostId, key));
            }
        });
        adapter = adapter(Duration.ofSeconds(5), Duration.ofMillis(300));

        var receipt = adapter.publish(request(key));

        assertThat(receipt.externalPostId()).isEqualTo(externalPostId);
        assertThat(receipt.replayed()).isTrue();
    }

    @Test
    void lookup404RetriesPostWithTheSameKey() throws Exception {
        var key = UUID.randomUUID();
        var externalPostId = UUID.randomUUID();
        var postCalls = new AtomicInteger();
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/channel-api/v1/posts")
                    && exchange.getRequestMethod().equals("POST")) {
                postCalls.incrementAndGet();
                receivedIdempotencyKeys.add(exchange.getRequestHeaders()
                        .getFirst("Idempotency-Key"));
                if (postCalls.get() == 1) {
                    respondWithStall(exchange, 201, """
                            {"externalPostId":"%s","idempotencyKey":"%s","channel":"BLOG",
                             "publishedAt":"2026-08-13T12:00:00Z","replayed":false}
                            """.formatted(externalPostId, key), 2_000);
                    return;
                }
                respond(exchange, 201, """
                        {"externalPostId":"%s","idempotencyKey":"%s","channel":"BLOG",
                         "publishedAt":"2026-08-13T12:00:00Z","replayed":false}
                        """.formatted(externalPostId, key));
            } else {
                respond(exchange, 404, """
                        {"code":"CHANNEL_POST_NOT_FOUND","message":"not found"}
                        """);
            }
        });
        adapter = adapter(Duration.ofSeconds(5), Duration.ofMillis(300));

        var receipt = adapter.publish(request(key));

        assertThat(postCalls.get()).isEqualTo(2);
        assertThat(receivedIdempotencyKeys).containsExactly(key.toString(), key.toString());
        assertThat(receipt.externalPostId()).isEqualTo(externalPostId);
        assertThat(receipt.replayed()).isFalse();
    }

    @Test
    void conflict409MapsToPermanentRejection() throws Exception {
        var key = UUID.randomUUID();
        startServer(exchange -> respond(exchange, 409, """
                {"code":"IDEMPOTENCY_CONFLICT","message":"different payload"}
                """));
        adapter = adapter(Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.publish(request(key)))
                .isInstanceOf(ChannelRejectedException.class)
                .satisfies(error -> {
                    var rejected = (ChannelRejectedException) error;
                    assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(rejected.getMessage()).isEqualTo("different payload");
                });
    }

    @Test
    void serverErrorMapsToRetryableFailure() throws Exception {
        startServer(exchange -> respond(exchange, 500, "boom"));
        adapter = adapter(Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.publish(request(UUID.randomUUID())))
                .isInstanceOf(ChannelUnavailableException.class);
    }

    @Test
    void connectionErrorMapsToRetryableFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        adapter = adapterAt(port, Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.publish(request(UUID.randomUUID())))
                .isInstanceOf(ChannelUnavailableException.class);
    }

    @Test
    void logsNeverContainContentPayload() throws Exception {
        var key = UUID.randomUUID();
        startServer(exchange -> respond(exchange, 500, "boom"));
        adapter = adapter(Duration.ofSeconds(5), Duration.ofSeconds(5));

        var appender = attachLogAppender();
        try {
            assertThatThrownBy(() -> adapter.publish(request(key)))
                    .isInstanceOf(ChannelUnavailableException.class);
        } finally {
            detachLogAppender(appender);
        }
        String logOutput = String.join("\n", appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logOutput).doesNotContain(SENSITIVE_MARKER);
    }

    private PublishRequest request(UUID key) {
        return new PublishRequest(31L, 12L, key, CampaignChannel.BLOG,
                Map.of("title", "T", "body", SENSITIVE_MARKER), List.of("source-1"));
    }

    private ChannelHttpAdapter adapter(Duration connectTimeout, Duration readTimeout)
            throws IOException {
        return adapterAt(server.getAddress().getPort(), connectTimeout, readTimeout);
    }

    private ChannelHttpAdapter adapterAt(int port, Duration connectTimeout,
                                         Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/channel-api/v1")
                .requestFactory(factory)
                .build();
        return new ChannelHttpAdapter(client, new ObjectMapper().findAndRegisterModules());
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // client already gone after a timeout
        }
    }

    /** Sends the response headers immediately and stalls the body so the client read times out. */
    private static void respondWithStall(HttpExchange exchange, int status, String body,
                                         long stallMillis) {
        try {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            sleep(stallMillis);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // client already gone after a timeout
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChannelHttpAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ChannelHttpAdapter.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
