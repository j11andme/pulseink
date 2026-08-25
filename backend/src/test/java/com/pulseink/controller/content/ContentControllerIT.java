package com.pulseink.controller.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake"
})
class ContentControllerIT {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static { MYSQL.start(); }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContentWorkflowRepository repository;

    private final HttpClient http = HttpClient.newHttpClient();
    private long runId;
    private long contentId;

    @BeforeEach
    void seedContent() {
        jdbc.execute("DELETE FROM approval_record");
        jdbc.execute("DELETE FROM review_issue");
        jdbc.execute("DELETE FROM review_report");
        jdbc.execute("DELETE FROM content_version");
        jdbc.execute("DELETE FROM content_item");
        jdbc.execute("DELETE FROM run_checkpoint");
        jdbc.execute("DELETE FROM run_event");
        jdbc.execute("DELETE FROM campaign_run");
        jdbc.execute("DELETE FROM campaign");
        jdbc.execute("DELETE FROM app_user");
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, role, enabled)
                VALUES (1, 'editor', 'hash', 'EDITOR', TRUE),
                       (2, 'viewer', 'hash', 'VIEWER', TRUE)
                """);
        jdbc.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json,
                     status, created_by, version)
                VALUES ('Campaign', 'objective', 'audience', '[\"BLOG\"]', '[]',
                        'DRAFT', 1, 0)
                """);
        long campaignId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO campaign_run
                    (campaign_id, requested_policy, selected_mode, state, version)
                VALUES (?, 'ORCHESTRATED', 'ORCHESTRATED', 'WAITING_APPROVAL', 0)
                """, campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        repository.captureAgentVersion(runId, "create-blog", AgentArtifact.create(
                "draft-1", runId, "create-blog", ArtifactType.CONTENT_DRAFT, 1,
                Map.of("title", "Agent", "body", "Draft"), List.of("source-1"),
                Instant.now()));
        contentId = repository.findByRunId(runId).getFirst().id();
    }

    @Test
    void authenticatedUserCanReadRunContentsAndDetail() throws Exception {
        var token = token(2L, "VIEWER");

        var list = get("/api/runs/" + runId + "/contents", token);
        var detail = get("/api/contents/" + contentId, token);

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"taskId\":\"create-blog\"");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"currentVersionNo\":1");
    }

    @Test
    void editorCanAppendAndApproveLatestVersionButNotOldVersion() throws Exception {
        var token = token(1L, "EDITOR");
        var initial = repository.findById(contentId).orElseThrow();

        var edited = post("/api/contents/" + contentId + "/versions", token, """
                {"expectedCurrentVersionNo":1,"expectedItemVersion":%d,
                 "content":{"title":"Human","body":"Edited"},
                 "sourceRefs":["source-1"]}
                """.formatted(initial.version()));
        assertThat(edited.statusCode()).isEqualTo(201);

        var current = repository.findById(contentId).orElseThrow();
        var oldApproval = post("/api/contents/" + contentId + "/approve", token, """
                {"contentVersionId":%d,"expectedCurrentVersionNo":2,
                 "expectedItemVersion":%d,"comment":"old"}
                """.formatted(initial.versions().getFirst().id(), current.version()));
        assertThat(oldApproval.statusCode()).isEqualTo(409);
        assertThat(oldApproval.body()).contains("\"code\":\"CONTENT_NOT_LATEST\"");

        var latestVersion = current.versions().getLast();
        var approved = post("/api/contents/" + contentId + "/approve", token, """
                {"contentVersionId":%d,"expectedCurrentVersionNo":2,
                 "expectedItemVersion":%d,"comment":"confirmed"}
                """.formatted(latestVersion.id(), current.version()));
        assertThat(approved.statusCode()).isEqualTo(201);
        assertThat(repository.findById(contentId).orElseThrow().approvals()).hasSize(1);
    }

    @Test
    void viewerCannotEditOrApprove() throws Exception {
        var token = token(2L, "VIEWER");
        assertThat(post("/api/contents/" + contentId + "/versions", token, """
                {"expectedCurrentVersionNo":1,"expectedItemVersion":1,
                 "content":{"body":"no"},"sourceRefs":[]}
                """).statusCode()).isEqualTo(403);
        assertThat(post("/api/contents/" + contentId + "/approve", token, """
                {"contentVersionId":1,"expectedCurrentVersionNo":1,
                 "expectedItemVersion":1,"comment":"no"}
                """).statusCode()).isEqualTo(403);
    }

    @Test
    void anonymousReadIsUnauthorized() throws Exception {
        assertThat(get("/api/runs/" + runId + "/contents", null).statusCode())
                .isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private String token(long uid, String role) {
        var claims = JwtClaimsSet.builder().subject("test-" + uid)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("uid", uid).claim("roles", List.of(role)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @AfterAll static void stopMySql() { MYSQL.stop(); }
}
