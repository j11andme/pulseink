package com.pulseink.client.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.embedding.EmbeddingPort.EmbeddingException;
import com.pulseink.service.embedding.EmbeddingPurpose;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingAdapterTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(request, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode(), 0);
            try (var out = exchange.getResponseBody()) {
                out.write(responseBody().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    private int statusCode() {
        return 200;
    }

    private String responseBody() {
        return """
                {"data":[
                  {"index":0,"embedding":[0.1,0.2,0.3,0.4]},
                  {"index":1,"embedding":[0.5,0.6,0.7,0.8]}
                ]}
                """;
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleEmbeddingAdapter adapter() {
        return new OpenAiCompatibleEmbeddingAdapter(
                "http://localhost:" + port, "test-key", "embed-model",
                4, "dimensions", 16, Duration.ofSeconds(5));
    }

    @Test
    void postsModelInputAndDimensionFieldThenRestoresOrder() {
        var adapter = adapter();
        var batch = adapter.embed(List.of("a", "b"), EmbeddingPurpose.INDEX);

        assertThat(batch.vectors()).hasSize(2);
        assertThat(batch.vectors().get(0)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(batch.vectors().get(1)).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
        assertThat(lastBody.get()).contains("\"model\":\"embed-model\"");
        assertThat(lastBody.get()).contains("\"input\":[\"a\",\"b\"]");
        assertThat(lastBody.get()).contains("\"dimensions\":4");
        assertThat(lastBody.get()).doesNotContain("test-key");
    }

    @Test
    void usesDimensionFieldModes() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(responseBody().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        var none = new OpenAiCompatibleEmbeddingAdapter(
                "http://localhost:" + port, "k", "m", 4, "none", 16, Duration.ofSeconds(5));
        none.embed(List.of("a", "b"), EmbeddingPurpose.INDEX);
        assertThat(lastBody.get()).doesNotContain("dimensions");

        var dimension = new OpenAiCompatibleEmbeddingAdapter(
                "http://localhost:" + port, "k", "m", 4, "dimension", 16, Duration.ofSeconds(5));
        dimension.embed(List.of("a", "b"), EmbeddingPurpose.INDEX);
        assertThat(lastBody.get()).contains("\"dimension\":4");
    }

    @Test
    void restoresOrderFromOutOfOrderIndices() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("""
                        {"data":[
                          {"index":1,"embedding":[0.5,0.6,0.7,0.8]},
                          {"index":0,"embedding":[0.1,0.2,0.3,0.4]}
                        ]}
                        """.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        var batch = adapter().embed(List.of("a", "b"), EmbeddingPurpose.INDEX);
        assertThat(batch.vectors().get(0)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(batch.vectors().get(1)).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
    }

    @Test
    void rejectsCountDimensionAndIndexErrors() throws IOException {
        for (String body : List.of(
                """
                {"data":[]}
                """,
                """
                {"data":[{"index":0,"embedding":[0.1,0.2]}]}
                """,
                """
                {"data":[{"index":3,"embedding":[0.1,0.2,0.3,0.4]}]}
                """,
                """
                {"data":[{"index":0,"embedding":[0.1,NaN,0.3,0.4]}]}
                """)) {
            server.stop(0);
            server = HttpServer.create(new InetSocketAddress(0), 0);
            port = server.getAddress().getPort();
            String captured = body;
            server.createContext("/embeddings", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write(captured.getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();

            assertThatThrownBy(() -> adapter().embed(List.of("a"), EmbeddingPurpose.INDEX))
                    .isInstanceOf(EmbeddingException.class);
        }
    }

    @Test
    void mapsHttpStatusErrorsToSanitizedDomainException() throws IOException {
        for (int status : List.of(401, 403, 429, 500, 503)) {
            server.stop(0);
            server = HttpServer.create(new InetSocketAddress(0), 0);
            port = server.getAddress().getPort();
            int code = status;
            server.createContext("/embeddings", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(code, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write("{\"error\":\"secret provider detail\"}".getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();

            var thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> adapter().embed(List.of("a"), EmbeddingPurpose.INDEX));
            assertThat(thrown).isInstanceOf(EmbeddingException.class);
            assertThat(thrown.getMessage()).doesNotContain("secret provider detail");
            assertThat(thrown.getMessage()).doesNotContain("test-key");
        }
    }

    @Test
    void timesOutWhenServerIsSilent() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            // never respond
        });
        server.start();

        var slow = new OpenAiCompatibleEmbeddingAdapter(
                "http://localhost:" + port, "k", "m", 4, "dimensions", 16,
                Duration.ofMillis(200));
        assertThatThrownBy(() -> slow.embed(List.of("a"), EmbeddingPurpose.INDEX))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void malformedJsonIsRejected() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("{not json".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        assertThatThrownBy(() -> adapter().embed(List.of("a"), EmbeddingPurpose.INDEX))
                .isInstanceOf(EmbeddingException.class);
    }
}
