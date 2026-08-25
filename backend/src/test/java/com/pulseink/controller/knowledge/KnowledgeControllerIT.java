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
            "spring.elasticsearch.uris=http://127.0.0.1:1",
            "pulseink.knowledge.storage-root=./target/knowledge-it",
            "pulseink.knowledge.max-file-bytes=64"
        })
class KnowledgeControllerIT {

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
        jdbcTemplate.execute("DELETE FROM approval_record");
        jdbcTemplate.execute("DELETE FROM review_issue");
        jdbcTemplate.execute("DELETE FROM review_report");
        jdbcTemplate.execute("DELETE FROM content_version");
        jdbcTemplate.execute("DELETE FROM content_item");
        jdbcTemplate.execute("DELETE FROM run_checkpoint");
        jdbcTemplate.execute("DELETE FROM run_event");
        jdbcTemplate.execute("DELETE FROM campaign_run");
        jdbcTemplate.execute("DELETE FROM campaign");
    }

    @Test
    void editorUploadsDocumentAndGets202WithPublicFieldsOnly() throws Exception {
        var token = token(1L, "EDITOR");
        var boundary = "pulseink-" + System.nanoTime();
        var body = multipart(boundary, "knowledge", "guide.md", "text/markdown",
                "knowledgeType", "PRODUCT", "authority", "OFFICIAL");

        var response = post("/api/knowledge/documents", token, boundary, body);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"status\":\"PENDING\"");
        assertThat(response.body()).doesNotContain("storageKey");
        assertThat(response.body()).doesNotContain("/data/");
        var rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document", Long.class);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void configuredFileLimitIsEnforcedInsteadOfTrustingMultipartSize() throws Exception {
        var token = token(1L, "EDITOR");
        var boundary = "pulseink-" + System.nanoTime();
        var body = multipart(boundary, "x".repeat(65), "large.md", "text/markdown",
                "knowledgeType", "PRODUCT", "authority", "OFFICIAL");

        var response = post("/api/knowledge/documents", token, boundary, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("KNOWLEDGE_FILE_TOO_LARGE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document", Long.class)).isZero();
    }

    @Test
    void duplicateDocumentIsRejectedWithStableConflict() throws Exception {
        var token = token(1L, "EDITOR");
        var firstBoundary = "pulseink-" + System.nanoTime();
        var firstBody = multipart(firstBoundary, "same", "same.md", "text/markdown",
                "knowledgeType", "PRODUCT", "authority", "OFFICIAL");
        assertThat(post("/api/knowledge/documents", token, firstBoundary, firstBody).statusCode())
                .isEqualTo(202);

        var secondBoundary = "pulseink-" + System.nanoTime();
        var secondBody = multipart(secondBoundary, "same", "same.md", "text/markdown",
                "knowledgeType", "PRODUCT", "authority", "OFFICIAL");
        var response = post("/api/knowledge/documents", token, secondBoundary, secondBody);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("KNOWLEDGE_DOCUMENT_DUPLICATE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document", Long.class)).isEqualTo(1L);
    }

    @Test
    void listReturnsStableOrderingWithoutStorageKeys() throws Exception {
        var token = token(1L, "EDITOR");
        var boundary = "pulseink-" + System.nanoTime();
        post("/api/knowledge/documents", token, boundary,
                multipart(boundary, "knowledge", "a.md", "text/markdown",
                        "knowledgeType", "PRODUCT", "authority", "OFFICIAL"));

        var response = get("/api/knowledge/documents?page=0&size=20", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"total\":1");
        assertThat(response.body()).contains("originalFilename");
        assertThat(response.body()).doesNotContain("storageKey");
    }

    @Test
    void retryUnknownDocumentReturns404() throws Exception {
        var token = token(1L, "EDITOR");
        var response = post("/api/knowledge/documents/999999/retry", token);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("KNOWLEDGE_DOCUMENT_NOT_FOUND");
    }

    @Test
    void searchTestWithoutElasticsearchReturnsStableUnavailable() throws Exception {
        var token = token(1L, "EDITOR");
        var response = post("/api/knowledge/search-test", token,
                "{\"query\":\"brand\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("KNOWLEDGE_INDEX_UNAVAILABLE");
    }

    @Test
    void invalidPageAndSizeAreRejected() throws Exception {
        var token = token(1L, "EDITOR");
        assertThat(get("/api/knowledge/documents?page=-1&size=20", token).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/knowledge/documents?page=0&size=101", token).statusCode())
                .isEqualTo(400);
    }

    private String multipart(String boundary, String fileContent, String filename,
                             String contentType, String... keyValues) {
        var sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename).append("\"\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n\r\n")
                .append(fileContent).append("\r\n");
        for (int i = 0; i < keyValues.length; i += 2) {
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"")
                    .append(keyValues[i]).append("\"\r\n\r\n")
                    .append(keyValues[i + 1]).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    private HttpResponse<String> post(String path, String token, String boundary, String body)
            throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return post(path, token, "");
    }

    private HttpResponse<String> post(String path, String token, String json) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
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
