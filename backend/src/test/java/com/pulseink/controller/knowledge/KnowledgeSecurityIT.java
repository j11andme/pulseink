package com.pulseink.controller.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "pulseink.auth.jwt-secret=01234567890123456789012345678901",
            "pulseink.auth.demo-password=pulseink-demo",
            "pulseink.model.provider=fake",
            "pulseink.knowledge.storage-root=./target/knowledge-security-it"
        })
class KnowledgeSecurityIT {

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

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM ingestion_job");
        jdbcTemplate.execute("DELETE FROM knowledge_document");
    }

    @Test
    void anonymousUploadAndListAreUnauthorized() throws Exception {
        assertThat(postAnonymous("/api/knowledge/documents").statusCode()).isEqualTo(401);
        assertThat(getAnonymous("/api/knowledge/documents").statusCode()).isEqualTo(401);
    }

    @Test
    void viewerCannotUploadOrRetryButCanList() throws Exception {
        var viewer = token(3L, "VIEWER");
        assertThat(post("/api/knowledge/documents", viewer).statusCode()).isEqualTo(403);
        assertThat(post("/api/knowledge/documents/1/retry", viewer).statusCode()).isEqualTo(403);
        assertThat(get("/api/knowledge/documents", viewer).statusCode()).isEqualTo(200);
    }

    @Test
    void adminCanUpload() throws Exception {
        var admin = token(2L, "ADMIN");
        var boundary = "pulseink-" + System.nanoTime();
        var body = "Content-Disposition: form-data; name=\"file\"; filename=\"a.md\"";
        assertThat(postMultipart("/api/knowledge/documents", admin, boundary, body).statusCode())
                .isEqualTo(202);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(String path, String token, String boundary,
                                               String disposition) throws Exception {
        var body = "--" + boundary + "\r\n" + disposition
                + "\r\nContent-Type: text/markdown\r\n\r\ncontent\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"knowledgeType\"\r\n\r\nPRODUCT\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"authority\"\r\n\r\nOFFICIAL\r\n"
                + "--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAnonymous(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAnonymous(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String token(long uid, String... roles) {
        var claims = JwtClaimsSet.builder()
                .subject("test-" + uid)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("uid", uid)
                .claim("roles", List.of(roles))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
