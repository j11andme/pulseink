package com.pulseink.controller.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "pulseink.auth.jwt-secret=01234567890123456789012345678901",
            "pulseink.auth.demo-password=pulseink-demo",
            "pulseink.model.provider=fake"
        })
@ActiveProfiles("local")
class ModelChatControllerIT {

    private static final Pattern ACCESS_TOKEN =
            Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void authenticatedPostStreamsTheModelEventContractInOrder() throws Exception {
        var response = chat(accessToken(), """
                {"message":"介绍 PulseInk","temperature":0.3,"maxTokens":512}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");
        assertThat(response.body())
                .containsSubsequence(
                        "event:started",
                        "\"provider\":\"fake\"",
                        "event:content_delta",
                        "\"content\":\"Pulse\"",
                        "event:content_delta",
                        "\"content\":\"Ink\"",
                        "event:completed",
                        "\"finishReason\":\"STOP\"");
    }

    @Test
    void blankInputReturnsBadRequestBeforeAStreamStarts() throws Exception {
        var response = chat(accessToken(), "{\"message\":\"  \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_MODEL_INPUT\"");
        assertThat(response.body()).contains("message must not be blank");
        assertThat(response.body()).doesNotContain("event:started");
    }

    @Test
    void anonymousCallIsRejectedBeforeReachingTheModel() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/model/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello\"}"))
                .build();

        var response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("event:started");
    }

    private HttpResponse<String> chat(String token, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/model/chat"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String accessToken() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"demo\",\"password\":\"pulseink-demo\"}"))
                .build();
        var response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());
        var matcher = ACCESS_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
