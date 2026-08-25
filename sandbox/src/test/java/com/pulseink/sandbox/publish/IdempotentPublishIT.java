package com.pulseink.sandbox.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.support.SandboxTestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pulseink.outbox.publisher-enabled=false")
class IdempotentPublishIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SandboxTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", SandboxTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", SandboxTestInfra::datasourcePassword);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM event_outbox");
        jdbc.execute("DELETE FROM channel_metric");
        jdbc.execute("DELETE FROM channel_post");
    }

    @Test
    void firstPostReturns201AndCreatesOneAggregate() throws Exception {
        var key = UUID.randomUUID();
        var response = publish(key, body("PulseInk", "Hello world"));

        assertThat(response.statusCode()).isEqualTo(201);
        var json = objectMapper.readTree(response.body());
        assertThat(json.get("idempotencyKey").asText()).isEqualTo(key.toString());
        assertThat(json.get("channel").asText()).isEqualTo("BLOG");
        assertThat(json.get("replayed").asBoolean()).isFalse();
        assertThat(json.get("externalPostId").asText()).isNotBlank();

        assertThat(count("channel_post")).isEqualTo(1);
        assertThat(count("channel_metric")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    void sameKeyAndSameBodyReplaysTheOriginalPost() throws Exception {
        var key = UUID.randomUUID();
        var payload = body("PulseInk", "Hello world");
        var first = publish(key, payload);
        var second = publish(key, payload);

        assertThat(second.statusCode()).isEqualTo(200);
        var firstJson = objectMapper.readTree(first.body());
        var secondJson = objectMapper.readTree(second.body());
        assertThat(secondJson.get("externalPostId").asText())
                .isEqualTo(firstJson.get("externalPostId").asText());
        assertThat(secondJson.get("replayed").asBoolean()).isTrue();

        assertThat(count("channel_post")).isEqualTo(1);
        assertThat(count("channel_metric")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyConflictsWithoutOverwriting() throws Exception {
        var key = UUID.randomUUID();
        var first = publish(key, body("Original", "Body one"));
        var conflicting = publish(key, body("Changed", "Body two"));

        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(conflicting.body()).contains("\"code\":\"IDEMPOTENCY_CONFLICT\"");
        assertThat(count("channel_post")).isEqualTo(1);
        var stored = jdbc.queryForObject(
                "SELECT content_json FROM channel_post WHERE idempotency_key = ?",
                String.class, key.toString());
        assertThat(stored).contains("Original").doesNotContain("Changed");
    }

    @Test
    void receiptLookupByKeyReturnsExactPostAndUnknownKeyIs404() throws Exception {
        var key = UUID.randomUUID();
        var first = publish(key, body("Lookup", "Receipt body"));
        var externalPostId = objectMapper.readTree(first.body()).get("externalPostId").asText();

        var found = get("/channel-api/v1/posts/by-idempotency-key/" + key, null);
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(found.body()).get("externalPostId").asText())
                .isEqualTo(externalPostId);

        var missing = get("/channel-api/v1/posts/by-idempotency-key/" + UUID.randomUUID(), null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"CHANNEL_POST_NOT_FOUND\"");
    }

    @Test
    void concurrentSameKeyRequestsCreateExactlyOnePost() throws Exception {
        var key = UUID.randomUUID();
        var payload = body("Concurrent", "Same payload");
        var start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                start.await();
                return publish(key, payload);
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                start.await();
                return publish(key, payload);
            });
            start.countDown();

            var firstResponse = first.get();
            var secondResponse = second.get();
            assertThat(firstResponse.statusCode()).isIn(200, 201);
            assertThat(secondResponse.statusCode()).isIn(200, 201);
            assertThat(objectMapper.readTree(firstResponse.body()).get("externalPostId").asText())
                    .isEqualTo(objectMapper.readTree(secondResponse.body())
                            .get("externalPostId").asText());
        }

        assertThat(count("channel_post")).isEqualTo(1);
        assertThat(count("channel_metric")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    private HttpResponse<String> publish(UUID key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/channel-api/v1/posts"))
                        .header("Idempotency-Key", key.toString())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String key) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).GET();
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String title, String body) {
        return """
                {"sourcePublicationId":31,"contentVersionId":12,"channel":"BLOG",
                 "content":{"title":"%s","body":"%s"},
                 "sourceRefs":["knowledge:document:1:chunk:2"]}
                """.formatted(title, body);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
