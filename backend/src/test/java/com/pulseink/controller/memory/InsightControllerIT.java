package com.pulseink.controller.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
class InsightControllerIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + MemoryElasticsearchTestContainer.httpHostAddress());
        registry.add("pulseink.memory.index-alias",
                () -> "pulseink-memory-controller-it-" + UUID.randomUUID().toString()
                        .substring(0, 8));
    }

    @LocalServerPort private int port;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
                VALUES (1,'editor','x','EDITOR',TRUE),(2,'viewer','x','VIEWER',TRUE)
                """);
        jdbc.update("""
                INSERT INTO campaign(id,name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES (1,'c','o','a','[\"BLOG\",\"SOCIAL\"]','[]','DRAFT',1,0)
                """);
        seedRun(2L, 10L, 11L, 1L, 21L, 100);
    }

    @Test
    void editorGeneratesCandidateAndReplayReturnsTheSameInsight() throws Exception {
        var first = post("/api/runs/2/insight-candidates", token(1L, "EDITOR"), "");
        var second = post("/api/runs/2/insight-candidates", token(1L, "EDITOR"), "");

        assertThat(first.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(first.body());
        long insightId = body.get("id").asLong();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("indexStatus").asText()).isEqualTo("NOT_INDEXED");
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("evidenceRefs")).hasSize(1);
        assertThat(first.body()).doesNotContain("embedding");
        assertThat(first.body()).doesNotContain("promptVersion");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(second.body()).get("id").asLong())
                .isEqualTo(insightId);
    }

    @Test
    void decisionApproveAndRejectPersistHumanReview() throws Exception {
        var candidate = objectMapper.readTree(post("/api/runs/2/insight-candidates",
                token(1L, "EDITOR"), "").body());

        var approve = post("/api/insights/" + candidate.get("id").asLong() + "/decision",
                token(1L, "EDITOR"), """
                        {"decision":"APPROVE","comment":"看起来成立"}
                        """);
        assertThat(approve.statusCode()).isEqualTo(200);
        var approved = objectMapper.readTree(approve.body());
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("indexStatus").asText()).isEqualTo("INDEX_PENDING");
        assertThat(approved.get("reviewedBy").asLong()).isEqualTo(1L);

        var rejected = post("/api/insights/" + candidate.get("id").asLong() + "/decision",
                token(1L, "EDITOR"), """
                        {"decision":"REJECT","comment":"改主意了"}
                        """);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(rejected.body()).contains("\"code\":\"INSIGHT_DECISION_CONFLICT\"");
    }

    @Test
    void listByCampaignReturnsCandidatesInDescendingOrder() throws Exception {
        seedRun(3L, 30L, 31L, 2L, 41L, 200);
        var first = post("/api/runs/2/insight-candidates", token(1L, "EDITOR"), "");
        var second = post("/api/runs/3/insight-candidates", token(1L, "EDITOR"), "");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);

        var listed = get("/api/campaigns/1/insights", token(2L, "VIEWER"));
        assertThat(listed.statusCode()).isEqualTo(200);
        var items = objectMapper.readTree(listed.body());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asLong())
                .isGreaterThan(items.get(1).get("id").asLong());
    }

    @Test
    void searchRequiresQueryAndReturnsEmptyWhileNothingIndexed() throws Exception {
        var missing = get("/api/insights/search", token(2L, "VIEWER"));
        assertThat(missing.statusCode()).isEqualTo(400);

        var empty = get("/api/insights/search?query=短句", token(2L, "VIEWER"));
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(empty.body())).isEmpty();
    }

    @Test
    void invalidDecisionValueIs400() throws Exception {
        var candidate = objectMapper.readTree(post("/api/runs/2/insight-candidates",
                token(1L, "EDITOR"), "").body());

        var invalid = post("/api/insights/" + candidate.get("id").asLong() + "/decision",
                token(1L, "EDITOR"), """
                        {"decision":"MAYBE","comment":""}
                        """);

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    private void seedRun(long runId, long itemId, long versionId, long approvalId,
                         long publicationId, long views) {
        jdbc.update("""
                INSERT INTO campaign_run(id,campaign_id,requested_policy,state,version)
                VALUES (?,1,'ORCHESTRATED','PUBLISHING',0)
                """, runId);
        jdbc.update("""
                INSERT INTO content_item(id,run_id,task_id,current_version_no,version)
                VALUES (?,?,'create-blog',1,0)
                """, itemId, runId);
        jdbc.update("""
                INSERT INTO content_version(id,content_item_id,version_no,content_json,
                                            source_refs_json,origin)
                VALUES (?,?,1,'{\"title\":\"T\",\"body\":\"hello\"}','[]','HUMAN')
                """, versionId, itemId);
        jdbc.update("""
                INSERT INTO approval_record(id,content_version_id,actor_id,comment_text)
                VALUES (?,?,1,'ok')
                """, approvalId, versionId);
        jdbc.update("""
                INSERT INTO publication(id,run_id,content_version_id,approval_record_id,
                                        requested_by,channel,idempotency_key,status,
                                        next_attempt_at,version,external_post_id,published_at)
                VALUES (?,?,?,?,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0,?,UTC_TIMESTAMP(6))
                """, publicationId, runId, versionId, approvalId,
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (?, '2026-08-13', ?, 12, 4)
                """, publicationId, views);
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
            return new SnapshotAwareInsightPort();
        }
    }

    /**
     * Reads the snapshot ids from the generator prompt and answers a matching valid
     * InsightCandidate, so every seeded run produces a working candidate fixture.
     */
    static final class SnapshotAwareInsightPort implements AgentModelPort {

        private static final Pattern VERSION = Pattern.compile(
                "\"contentVersionId\":(\\d+)");
        private static final Pattern PUBLICATION = Pattern.compile(
                "\"publicationId\":(\\d+)");

        @Override
        public ModelStreamHandle stream(ModelRequest request,
                                        Consumer<ModelStreamEvent> events) {
            long versionId = extract(VERSION, request.userPrompt());
            long publicationId = extract(PUBLICATION, request.userPrompt());
            events.accept(new ModelStreamEvent.Started(request.requestId(), "fake", "fake"));
            events.accept(new ModelStreamEvent.ContentDelta(request.requestId(), """
                    {"schemaVersion":1,"category":"CHANNEL_PATTERN",
                     "title":"社交短句互动更高","insightText":"短句形式能提升互动",
                     "scopeType":"CHANNEL","scopeValue":"SOCIAL",
                     "applicableChannels":["SOCIAL"],
                     "evidenceRefs":[{"contentVersionId":%d,"publicationId":%d,
                                      "metricFrom":"2026-08-13","metricTo":"2026-08-13"}],
                     "confidence":0.78,"limitations":["样本窗口较短"]}
                    """.formatted(versionId, publicationId)));
            events.accept(new ModelStreamEvent.Completed(request.requestId(), "STOP"));
            return () -> {};
        }

        private static long extract(Pattern pattern, String prompt) {
            Matcher matcher = pattern.matcher(prompt);
            if (!matcher.find()) {
                throw new IllegalStateException("snapshot id missing in model prompt");
            }
            return Long.parseLong(matcher.group(1));
        }
    }
}
