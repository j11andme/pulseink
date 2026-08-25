package com.pulseink.controller.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.support.BackendTestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false"
})
class PublicationControllerIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BackendTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", BackendTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", BackendTestInfra::datasourcePassword);
    }

    @LocalServerPort private int port;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private long runId;
    private long contentId;
    private long approvedVersionId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"content_metric_daily", "feedback_inbox", "publication",
                "approval_record", "review_issue", "review_report", "content_version",
                "content_item", "run_checkpoint", "run_event", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, role, enabled)
                VALUES (1, 'editor', 'hash', 'EDITOR', TRUE),
                       (2, 'viewer', 'hash', 'VIEWER', TRUE),
                       (3, 'admin', 'hash', 'ADMIN', TRUE)
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
                VALUES (?, 'DIRECT', 'DIRECT', 'WAITING_APPROVAL', 0)
                """, campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        contentId = seedContent(runId, "create-blog", 1, true);
        approvedVersionId = jdbc.queryForObject("""
                SELECT id FROM content_version
                WHERE content_item_id = ? AND version_no = 1
                """, Long.class, contentId);
    }

    @Test
    void editorCreateReturns202AndFlipsRunToPublishing() throws Exception {
        var response = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));

        assertThat(response.statusCode()).isEqualTo(202);
        var body = objectMapper.readTree(response.body());
        long publicationId = body.get("id").asLong();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("idempotencyKey").asText()).isNotBlank();
        assertThat(response.headers().firstValue("Location").orElse(""))
                .isEqualTo("/api/publications/" + publicationId);
        assertThat(runState()).isEqualTo("PUBLISHING");
    }

    @Test
    void adminCanCreateAndRepeatedPostReturnsTheSamePublication() throws Exception {
        var first = post("/api/contents/" + contentId + "/publications",
                token(3L, "ADMIN"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));
        var second = post("/api/contents/" + contentId + "/publications",
                token(3L, "ADMIN"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        var firstBody = objectMapper.readTree(first.body());
        var secondBody = objectMapper.readTree(second.body());
        assertThat(secondBody.get("id").asLong()).isEqualTo(firstBody.get("id").asLong());
        assertThat(secondBody.get("idempotencyKey").asText())
                .isEqualTo(firstBody.get("idempotencyKey").asText());
        assertThat(countPublications()).isEqualTo(1);
    }

    @Test
    void viewerIsForbiddenAndAnonymousIsUnauthenticated() throws Exception {
        var viewer = post("/api/contents/" + contentId + "/publications",
                token(2L, "VIEWER"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));
        var anonymous = post("/api/contents/" + contentId + "/publications", null, """
                {"contentVersionId":%d,"channel":"BLOG"}
                """.formatted(approvedVersionId));

        assertThat(viewer.statusCode()).isEqualTo(403);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(countPublications()).isZero();
    }

    @Test
    void unapprovedVersionReturns409ContentNotApproved() throws Exception {
        long unapproved = seedContent(runId, "create-blog-2", 1, false);
        long unapprovedVersion = jdbc.queryForObject("""
                SELECT id FROM content_version
                WHERE content_item_id = ? AND version_no = 1
                """, Long.class, unapproved);

        var response = post("/api/contents/" + unapproved + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(unapprovedVersion));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"CONTENT_NOT_APPROVED\"");
    }

    @Test
    void oldVersionReturns409ContentNotLatest() throws Exception {
        long item = seedContent(runId, "create-blog-3", 1, true);
        long oldVersion = jdbc.queryForObject("""
                SELECT id FROM content_version
                WHERE content_item_id = ? AND version_no = 1
                """, Long.class, item);
        jdbc.update("""
                INSERT INTO content_version
                    (content_item_id, version_no, content_json, source_refs_json, origin)
                VALUES (?, 2, '{\"body\":\"v2\"}', '[]', 'HUMAN')
                """, item);
        jdbc.update("UPDATE content_item SET current_version_no = 2 WHERE id = ?", item);

        var response = post("/api/contents/" + item + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(oldVersion));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"CONTENT_NOT_LATEST\"");
    }

    @Test
    void invalidBodyReturns400ValidationError() throws Exception {
        var badChannel = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"TELEVISION"}
                        """.formatted(approvedVersionId));
        var badVersion = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":0,"channel":"BLOG"}
                        """);

        assertThat(badChannel.statusCode()).isEqualTo(400);
        assertThat(badChannel.body()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(badVersion.statusCode()).isEqualTo(400);
        assertThat(badVersion.body()).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    @Test
    void runOutsidePublishableStatesReturns409PublicationConflict() throws Exception {
        jdbc.update("UPDATE campaign_run SET state = 'RUNNING' WHERE id = ?", runId);

        var response = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"PUBLICATION_CONFLICT\"");
    }

    @Test
    void unknownPublicationReturns404PublicationNotFound() throws Exception {
        var response = get("/api/publications/999999", token(2L, "VIEWER"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"PUBLICATION_NOT_FOUND\"");
    }

    @Test
    void listByRunIdReturnsStableOrder() throws Exception {
        var first = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"BLOG"}
                        """.formatted(approvedVersionId));
        var second = post("/api/contents/" + contentId + "/publications",
                token(1L, "EDITOR"), """
                        {"contentVersionId":%d,"channel":"SOCIAL"}
                        """.formatted(approvedVersionId));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);

        var listed = get("/api/runs/" + runId + "/publications", token(2L, "VIEWER"));
        assertThat(listed.statusCode()).isEqualTo(200);
        var items = objectMapper.readTree(listed.body());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asLong())
                .isLessThan(items.get(1).get("id").asLong());
        assertThat(items.get(0).get("channel").asText()).isEqualTo("BLOG");
        assertThat(items.get(1).get("channel").asText()).isEqualTo("SOCIAL");
    }

    private long seedContent(long runId, String taskId, int versionNo, boolean approved) {
        jdbc.update("""
                INSERT INTO content_item(run_id, task_id, current_version_no, version)
                VALUES (?, ?, ?, 0)
                """, runId, taskId, versionNo);
        long itemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO content_version
                    (content_item_id, version_no, content_json, source_refs_json, origin)
                VALUES (?, ?, '{\"title\":\"T\",\"body\":\"B\"}', '[\"source-1\"]', 'HUMAN')
                """, itemId, versionNo);
        long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (approved) {
            jdbc.update("""
                    INSERT INTO approval_record(content_version_id, actor_id, comment_text)
                    VALUES (?, 1, 'ok')
                    """, versionId);
        }
        return itemId;
    }

    private String runState() {
        return jdbc.queryForObject(
                "SELECT state FROM campaign_run WHERE id = ?", String.class, runId);
    }

    private int countPublications() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM publication", Integer.class);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String token(long uid, String role) {
        var claims = JwtClaimsSet.builder().subject("test-" + uid)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("uid", uid).claim("roles", List.of(role)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
