package com.pulseink.controller.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.support.MemoryTestContainers;
import com.pulseink.support.MemoryElasticsearchTestContainer;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Role matrix for the insight REST surface: EDITOR/ADMIN generate and decide, VIEWER only
 * lists/searches, anonymous gets 401 everywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.embedding.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false",
        "pulseink.memory.index-worker-enabled=false",
        "pulseink.run-lease.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class InsightSecurityIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + MemoryElasticsearchTestContainer.httpHostAddress());
        registry.add("pulseink.memory.index-alias",
                () -> "pulseink-memory-security-it-" + UUID.randomUUID().toString()
                        .substring(0, 8));
    }

    @LocalServerPort private int port;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"campaign_insight", "content_metric_daily",
                "feedback_inbox", "publication", "approval_record", "content_version",
                "content_item", "run_checkpoint", "run_event", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,role,enabled)
                VALUES (1,'editor','x','EDITOR',TRUE),(2,'viewer','x','VIEWER',TRUE),
                       (3,'admin','x','ADMIN',TRUE)
                """);
        jdbc.update("""
                INSERT INTO campaign(id,name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES (1,'c','o','a','[\"BLOG\",\"SOCIAL\"]','[]','DRAFT',1,0)
                """);
        jdbc.update("""
                INSERT INTO campaign_run(id,campaign_id,requested_policy,state,version)
                VALUES (2,1,'ORCHESTRATED','PUBLISHING',0)
                """);
        jdbc.update("""
                INSERT INTO content_item(id,run_id,task_id,current_version_no,version)
                VALUES (10,2,'create-blog',1,0)
                """);
        jdbc.update("""
                INSERT INTO content_version(id,content_item_id,version_no,content_json,
                                            source_refs_json,origin)
                VALUES (11,10,1,'{\"title\":\"T\",\"body\":\"hello\"}','[]','HUMAN')
                """);
        jdbc.update("""
                INSERT INTO approval_record(id,content_version_id,actor_id,comment_text)
                VALUES (1,11,1,'ok')
                """);
        jdbc.update("""
                INSERT INTO publication(id,run_id,content_version_id,approval_record_id,
                                        requested_by,channel,idempotency_key,status,
                                        next_attempt_at,version,external_post_id,published_at)
                VALUES (21,2,11,1,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0,?,UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (21, '2026-08-13', 100, 12, 4)
                """);
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        assertThat(post("/api/runs/2/insight-candidates", null, "").statusCode())
                .isEqualTo(401);
        assertThat(post("/api/insights/1/decision", null,
                "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(401);
        assertThat(get("/api/campaigns/1/insights", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/insights/search?query=x", null).statusCode()).isEqualTo(401);
    }

    @Test
    void viewerCanOnlyListAndSearch() throws Exception {
        assertThat(get("/api/campaigns/1/insights", token(2L, "VIEWER")).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/insights/search?query=短句", token(2L, "VIEWER")).statusCode())
                .isEqualTo(200);
        assertThat(post("/api/runs/2/insight-candidates",
                token(2L, "VIEWER"), "").statusCode()).isEqualTo(403);
        assertThat(post("/api/insights/1/decision",
                token(2L, "VIEWER"), "{\"decision\":\"APPROVE\"}").statusCode())
                .isEqualTo(403);
    }

    @Test
    void editorAndAdminCanGenerateAndDecide() throws Exception {
        var editorCandidate = post("/api/runs/2/insight-candidates",
                token(1L, "EDITOR"), "");
        assertThat(editorCandidate.statusCode()).isEqualTo(200);
        long insightId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(editorCandidate.body()).get("id").asLong();

        var adminDecision = post("/api/insights/" + insightId + "/decision",
                token(3L, "ADMIN"), "{\"decision\":\"APPROVE\"}");
        assertThat(adminDecision.statusCode()).isEqualTo(200);
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

    @TestConfiguration
    static class TestOverrides {

        @Bean("primaryModelPort")
        AgentModelPort snapshotAwareInsightModel() {
            return new InsightControllerIT.SnapshotAwareInsightPort();
        }
    }
}
