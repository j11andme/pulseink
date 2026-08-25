package com.pulseink.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            "pulseink.auth.demo-password=pulseink-demo"
        })
@ActiveProfiles("local")
class LoginControllerIT {

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
    void validDemoCredentialsReturnAThirtyMinuteEditorToken() throws Exception {
        var response = login("demo", "pulseink-demo");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"accessToken\":");
        assertThat(response.body()).contains("\"expiresIn\":1800");
        assertThat(response.body()).contains("\"username\":\"demo\"");
        assertThat(response.body()).contains("\"role\":\"EDITOR\"");
    }

    @Test
    void invalidPasswordReturnsTheSameUnauthorizedMessage() throws Exception {
        var response = login("demo", "wrong-password");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid username or password");
        assertThat(response.body()).doesNotContain("demo");
    }

    @Test
    void protectedApiOnlyAdvertisesBearerAuthentication() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/protected-probe"))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate"))
                .anySatisfy(value -> assertThat(value).containsIgnoringCase("Bearer"))
                .noneSatisfy(value -> assertThat(value).containsIgnoringCase("Basic"));
    }

    @Test
    void localWalkingSkeletonHealthStaysUpWithoutOptionalRedis() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
