package com.pulseink.controller.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.service.campaign.RunEventType;
import com.pulseink.service.campaign.RunJournal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
            "pulseink.run-lease.enabled=false",
            "pulseink.publication.worker-enabled=false",
            "pulseink.feedback.consumer-enabled=false"
        })
class RunTraceControllerIT {

    private static final Pattern RUN_LOCATION_ID = Pattern.compile("/api/runs/(\\d+)$");
    private static final Pattern CAMPAIGN_LOCATION_ID = Pattern.compile("/api/campaigns/(\\d+)$");

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

    @Autowired
    private RunJournal journal;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanTables() {
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
    void viewerCanReadTraceHistoryAndIntegrationsButCannotCreateRun() throws Exception {
        var editorToken = token(1L, "EDITOR");
        var viewerToken = token(3L, "VIEWER");
        var campaignId = createCampaign(editorToken);
        var runId = seedRun(campaignId, "DIRECT", "DIRECT",
                Instant.parse("2026-08-04T12:00:00Z"));

        assertThat(get("/api/runs/" + runId + "/trace", viewerToken).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/campaigns/" + campaignId + "/runs", viewerToken).statusCode())
                .isEqualTo(200);
        assertThat(get("/api/integrations", viewerToken).statusCode()).isEqualTo(200);

        var forbidden = post("/api/campaigns/" + campaignId + "/runs", viewerToken,
                directBody());
        assertThat(forbidden.statusCode()).isEqualTo(403);
    }

    @Test
    void emptyTraceReturnsNullCheckpointAndZeroSequence() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = seedCampaign("Empty trace");
        var runId = seedRun(campaignId, "DIRECT", "DIRECT",
                Instant.parse("2026-08-04T12:00:00Z"));

        var response = get("/api/runs/" + runId + "/trace", token);

        assertThat(response.statusCode()).isEqualTo(200);
        var json = objectMapper.readTree(response.body());
        assertThat(json.path("run").path("runId").asLong()).isEqualTo(runId);
        assertThat(json.path("run").path("state").asText()).isEqualTo("CREATED");
        assertThat(json.path("lastEventSequence").asLong()).isZero();
        assertThat(json.path("checkpoint").isNull()).isTrue();
        assertThat(json.path("events").isEmpty()).isTrue();
    }

    @Test
    void traceReturnsLatestCheckpointBudgetArtifactsAndOrderedEvents() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = seedCampaign("Trace with checkpoint");
        var runId = seedRun(campaignId, "ORCHESTRATED", "ORCHESTRATED",
                Instant.parse("2026-08-04T12:00:00Z"));

        var artifact = AgentArtifact.create(
                "run-" + runId + "-plan-v1", runId, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", "{\"schemaVersion\":1,\"tasks\":[]}"), List.of("chunk-1"),
                Instant.parse("2026-08-04T12:01:00Z"));
        var checkpoint = RunCheckpoint.of(
                runId, "ARTIFACT", List.of(artifact),
                new BudgetSnapshot(2, 3, 4000L, 1), 1, 0L,
                Instant.parse("2026-08-04T12:01:00Z"));
        journal.saveCheckpointAndAppendEvent(checkpoint, RunEventType.ARTIFACT_CREATED,
                Map.of("artifactId", artifact.artifactId()));
        journal.appendEvent(runId, RunEventType.RUN_STATE_CHANGED,
                Map.of("fromState", "RUNNING", "toState", "WAITING_APPROVAL"));

        var response = get("/api/runs/" + runId + "/trace", token);

        assertThat(response.statusCode()).isEqualTo(200);
        var json = objectMapper.readTree(response.body());
        assertThat(json.path("lastEventSequence").asLong()).isEqualTo(2L);
        var checkpointNode = json.path("checkpoint");
        assertThat(checkpointNode.path("checkpointType").asText()).isEqualTo("ARTIFACT");
        assertThat(checkpointNode.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(checkpointNode.path("lastCompletedRound").asInt()).isEqualTo(1);
        assertThat(checkpointNode.path("lastPersistedEventSequence").asLong()).isEqualTo(1L);
        assertThat(checkpointNode.path("budget").path("modelCallsUsed").asInt()).isEqualTo(2);
        assertThat(checkpointNode.path("budget").path("toolCallsUsed").asInt()).isEqualTo(3);
        assertThat(checkpointNode.path("budget").path("tokensUsed").asLong()).isEqualTo(4000L);
        assertThat(checkpointNode.path("budget").path("reactRoundsUsed").asInt()).isEqualTo(1);
        assertThat(checkpointNode.path("artifacts").size()).isEqualTo(1);
        var artifactNode = checkpointNode.path("artifacts").get(0);
        assertThat(artifactNode.path("artifactId").asText()).isEqualTo(artifact.artifactId());
        assertThat(artifactNode.path("taskId").asText()).isEqualTo("planner");
        assertThat(artifactNode.path("type").asText()).isEqualTo("PLAN");
        assertThat(artifactNode.path("artifactVersion").asInt()).isEqualTo(1);
        assertThat(artifactNode.path("status").asText()).isEqualTo("VALID");
        assertThat(artifactNode.path("sourceRefs").get(0).asText()).isEqualTo("chunk-1");
        assertThat(json.path("events").size()).isEqualTo(2);
        assertThat(json.path("events").get(0).path("sequence").asLong()).isEqualTo(1L);
        assertThat(json.path("events").get(0).path("eventType").asText())
                .isEqualTo("ARTIFACT_CREATED");
        assertThat(json.path("events").get(1).path("sequence").asLong()).isEqualTo(2L);
        assertThat(json.path("events").get(1).path("eventType").asText())
                .isEqualTo("RUN_STATE_CHANGED");
    }

    @Test
    void historyReturnsOnlyOwnRunsNewestFirst() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = seedCampaign("History owner");
        var otherCampaignId = seedCampaign("Other campaign");
        var firstRunId = seedRun(campaignId, "REACT", "REACT",
                Instant.parse("2026-08-04T12:00:00Z"));
        var secondRunId = seedRun(campaignId, "ORCHESTRATED", "ORCHESTRATED",
                Instant.parse("2026-08-04T13:00:00Z"));
        var otherRunId = seedRun(otherCampaignId, "DIRECT", "DIRECT",
                Instant.parse("2026-08-04T14:00:00Z"));

        var response = get("/api/campaigns/" + campaignId + "/runs", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get(0).path("runId").asLong()).isEqualTo(secondRunId);
        assertThat(json.get(0).path("requestedPolicy").asText()).isEqualTo("ORCHESTRATED");
        assertThat(json.get(0).has("failureReason")).isTrue();
        assertThat(json.get(0).has("startedAt")).isTrue();
        assertThat(json.get(0).has("completedAt")).isTrue();
        assertThat(json.get(0).has("createdAt")).isTrue();
        assertThat(json.get(1).path("runId").asLong()).isEqualTo(firstRunId);
        assertThat(json.findValues("runId").stream().map(JsonNode::asLong).toList())
                .doesNotContain(otherRunId);
    }

    @Test
    void missingCampaignAndRunReturnStable404() throws Exception {
        var token = token(1L, "EDITOR");

        var missingCampaign = get("/api/campaigns/999999/runs", token);
        assertThat(missingCampaign.statusCode()).isEqualTo(404);
        assertThat(missingCampaign.body()).contains("\"code\":\"CAMPAIGN_NOT_FOUND\"");

        var missingRun = get("/api/runs/999999/trace", token);
        assertThat(missingRun.statusCode()).isEqualTo(404);
        assertThat(missingRun.body()).contains("\"code\":\"RUN_NOT_FOUND\"");
    }

    @Test
    void anonymousTraceHistoryAndIntegrationsAreUnauthorized() throws Exception {
        assertThat(getAnonymous("/api/runs/1/trace").statusCode()).isEqualTo(401);
        assertThat(getAnonymous("/api/campaigns/1/runs").statusCode()).isEqualTo(401);
        assertThat(getAnonymous("/api/integrations").statusCode()).isEqualTo(401);
    }

    private long createCampaign(String token) throws Exception {
        var response = post("/api/campaigns", token, """
                {"name":"PulseInk 秋招发布","objective":"向 Java 后端开发者介绍 PulseInk",
                 "audience":"关注 Agent 工程化的 Java 开发者","channels":["BLOG","SOCIAL"],
                 "constraints":["事实性结论必须给出引用"]}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        var matcher = CAMPAIGN_LOCATION_ID.matcher(
                response.headers().firstValue("Location").orElse(""));
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private long seedCampaign(String name) {
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, 'objective', 'audience', '["BLOG","SOCIAL"]', '[]', 'DRAFT', 1, 0)
                """, name);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long seedRun(long campaignId, String requestedPolicy, String selectedMode,
                         Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO campaign_run
                    (campaign_id, requested_policy, selected_mode, selector_policy_version,
                     selection_reason_json, selection_feature_json, estimated_token_budget,
                     state, version, created_at, updated_at)
                VALUES (?, ?, ?, 'selector-v1', '[]', '{}', 20000, 'CREATED', 0, ?, ?)
                """, campaignId, requestedPolicy, selectedMode, createdAt, createdAt);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
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

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String directBody() {
        return """
                {"requestedPolicy":"DIRECT","taskProperties":{
                  "decomposability":0.1,"channelCount":2,"sourceDiversity":0,
                  "parallelResearchBranches":0,"sequentialDependency":0.1,
                  "factualRisk":0.1,"toolBreadth":0,"latencyBudgetMs":8000}}
                """;
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
