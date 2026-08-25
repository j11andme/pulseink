package com.pulseink.controller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
class RunControllerIT {

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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanRunsAndCampaigns() throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            Long activeRuns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM campaign_run "
                            + "WHERE state IN ('CREATED','PLANNING','RUNNING','REPLANNING')",
                    Long.class);
            if (activeRuns != null && activeRuns > 0) {
                Thread.sleep(200);
                continue;
            }
            try {
                jdbcTemplate.execute("DELETE FROM approval_record");
                jdbcTemplate.execute("DELETE FROM review_issue");
                jdbcTemplate.execute("DELETE FROM review_report");
                jdbcTemplate.execute("DELETE FROM content_version");
                jdbcTemplate.execute("DELETE FROM content_item");
                jdbcTemplate.execute("DELETE FROM run_checkpoint");
                jdbcTemplate.execute("DELETE FROM run_event");
                jdbcTemplate.execute("DELETE FROM campaign_run");
                jdbcTemplate.execute("DELETE FROM campaign");
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("cleanup failed after retries");
    }

    @Test
    void anonymousStartAndReadAreUnauthorized() throws Exception {
        assertThat(postAnonymous("/api/campaigns/1/runs", adaptiveBody()).statusCode())
                .isEqualTo(401);
        assertThat(getAnonymous("/api/runs/1/execution-decision").statusCode()).isEqualTo(401);
    }

    @Test
    void editorCanStartAdaptiveRunAndReadTheDecision() throws Exception {
        var editorToken = token(1L, "EDITOR");
        var campaignId = createCampaign(editorToken);

        var response = post("/api/campaigns/" + campaignId + "/runs", editorToken, adaptiveBody());

        assertThat(response.statusCode()).isEqualTo(201);
        var location = response.headers().firstValue("Location").orElse("");
        assertThat(location).startsWith("/api/runs/");
        var runId = extractRunId(location);
        assertThat(response.body()).contains("\"runId\":" + runId);
        assertThat(response.body()).contains("\"campaignId\":" + campaignId);
        assertThat(response.body()).contains("\"requestedPolicy\":\"ADAPTIVE\"");
        assertThat(response.body()).contains("\"selectedMode\":\"ORCHESTRATED\"");
        assertThat(response.body()).contains("\"selectorPolicyVersion\":\"selector-v1\"");
        assertThat(response.body()).contains("\"DECOMPOSABLE_OR_HIGH_RISK\"");
        assertThat(response.body()).contains("\"estimatedTokenBudget\":20000");
        assertThat(response.body()).contains("\"state\":\"CREATED\"");

        var decision = get("/api/runs/" + runId + "/execution-decision", editorToken);
        assertThat(decision.statusCode()).isEqualTo(200);
        assertThat(decision.body()).contains("\"runId\":" + runId);
        assertThat(decision.body()).contains("\"selectedMode\":\"ORCHESTRATED\"");
        assertThat(decision.body()).contains("\"featureSnapshot\"");
    }

    @Test
    void adminCanStartRun() throws Exception {
        var adminToken = token(2L, "ADMIN");
        var campaignId = createCampaign(adminToken);

        var response = post("/api/campaigns/" + campaignId + "/runs", adminToken, adaptiveBody());

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void viewerCannotStartRunButCanReadTheDecision() throws Exception {
        var editorToken = token(1L, "EDITOR");
        var viewerToken = token(3L, "VIEWER");
        var campaignId = createCampaign(editorToken);

        var forbidden = post("/api/campaigns/" + campaignId + "/runs", viewerToken, adaptiveBody());
        assertThat(forbidden.statusCode()).isEqualTo(403);

        var runId = startRun(editorToken, campaignId);
        assertThat(get("/api/runs/" + runId + "/execution-decision", viewerToken).statusCode())
                .isEqualTo(200);
    }

    @Test
    void manualFixedPolicyMapsDirectlyAndRecordsManualOverride() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);
        var body = """
                {"requestedPolicy":"DIRECT","taskProperties":{
                  "decomposability":0.8,"channelCount":2,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":0.8,"toolBreadth":3,"latencyBudgetMs":20000}}
                """;

        var response = post("/api/campaigns/" + campaignId + "/runs", token, body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"selectedMode\":\"DIRECT\"");
        assertThat(response.body()).contains("\"MANUAL_POLICY_OVERRIDE\"");
    }

    @Test
    void directRunIsLaunchedAndPersistsExecutionEvents() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);
        var body = """
                {"requestedPolicy":"DIRECT","taskProperties":{
                  "decomposability":0.1,"channelCount":2,"sourceDiversity":0,
                  "parallelResearchBranches":0,"sequentialDependency":0.1,
                  "factualRisk":0.1,"toolBreadth":0,"latencyBudgetMs":8000}}
                """;

        var response = post("/api/campaigns/" + campaignId + "/runs", token, body);

        assertThat(response.statusCode()).isEqualTo(201);
        var runId = extractRunId(response.headers().firstValue("Location").orElse(""));
        assertThat(response.body()).contains("\"selectedMode\":\"DIRECT\"");

        await(() -> {
            var events = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM run_event WHERE run_id = ?", Long.class, runId);
            assertThat(events).isGreaterThanOrEqualTo(3);
            var state = jdbcTemplate.queryForObject(
                    "SELECT state FROM campaign_run WHERE id = ?", String.class, runId);
            assertThat(state).isEqualTo("WAITING_APPROVAL");
            var eventTypes = jdbcTemplate.query(
                    "SELECT event_type FROM run_event WHERE run_id = ? ORDER BY sequence_no",
                    (rs, i) -> rs.getString(1), runId);
            assertThat(eventTypes).contains("EXECUTION_MODE_SELECTED", "ARTIFACT_CREATED");
        });
    }

    @Test
    void orchestratedRunIsLaunchedFromHttpAndProducesPlanAndTaskEvents() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);

        var response = post("/api/campaigns/" + campaignId + "/runs", token, adaptiveBody());

        assertThat(response.statusCode()).isEqualTo(201);
        var runId = extractRunId(response.headers().firstValue("Location").orElse(""));
        assertThat(response.body()).contains("\"selectedMode\":\"ORCHESTRATED\"");

        await(() -> {
            var state = jdbcTemplate.queryForObject(
                    "SELECT state FROM campaign_run WHERE id = ?", String.class, runId);
            assertThat(state).isNotEqualTo("CREATED");
            var eventTypes = jdbcTemplate.query(
                    "SELECT event_type FROM run_event WHERE run_id = ? ORDER BY sequence_no",
                    (rs, i) -> rs.getString(1), runId);
            assertThat(eventTypes).contains("PLAN_VALIDATED", "TASK_STARTED",
                    "ARTIFACT_CREATED");
        });
    }

    @AfterEach
    void waitForBackgroundRunsToSettle() throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            Long activeRuns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM campaign_run "
                            + "WHERE state IN ('CREATED','PLANNING','RUNNING','REPLANNING')",
                    Long.class);
            if (activeRuns == null || activeRuns == 0) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private void await(Runnable assertion) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError | RuntimeException ex) {
                last = ex;
                Thread.sleep(200);
            }
        }
        throw new AssertionError("condition not met within timeout", last);
    }

    @Test
    void bodySuppliedStateCannotOverrideServerValues() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);
        var body = """
                {"requestedPolicy":"ADAPTIVE","state":"RUNNING","taskProperties":{
                  "decomposability":0.8,"channelCount":2,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":0.8,"toolBreadth":3,"latencyBudgetMs":20000}}
                """;

        var response = post("/api/campaigns/" + campaignId + "/runs", token, body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"state\":\"CREATED\"");
        assertThat(response.body()).doesNotContain("RUNNING");
    }

    @Test
    void channelCountMismatchReturns400WithStableCode() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);
        var body = """
                {"requestedPolicy":"ADAPTIVE","taskProperties":{
                  "decomposability":0.8,"channelCount":3,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":0.8,"toolBreadth":3,"latencyBudgetMs":20000}}
                """;

        var response = post("/api/campaigns/" + campaignId + "/runs", token, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_RUN\"");
    }

    @Test
    void invalidEnumAndInvalidFeatureValuesReturn400() throws Exception {
        var token = token(1L, "EDITOR");
        var campaignId = createCampaign(token);

        var badPolicy = post("/api/campaigns/" + campaignId + "/runs", token, """
                {"requestedPolicy":"BOGUS","taskProperties":{
                  "decomposability":0.8,"channelCount":2,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":0.8,"toolBreadth":3,"latencyBudgetMs":20000}}
                """);
        assertThat(badPolicy.statusCode()).isEqualTo(400);
        assertThat(badPolicy.body()).contains("\"code\":\"INVALID_RUN\"");

        var badRisk = post("/api/campaigns/" + campaignId + "/runs", token, """
                {"requestedPolicy":"ADAPTIVE","taskProperties":{
                  "decomposability":0.8,"channelCount":2,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":1.5,"toolBreadth":3,"latencyBudgetMs":20000}}
                """);
        assertThat(badRisk.statusCode()).isEqualTo(400);
        assertThat(badRisk.body()).contains("\"code\":\"INVALID_RUN\"");
    }

    @Test
    void missingCampaignReturns404WithCampaignNotFoundCode() throws Exception {
        var token = token(1L, "EDITOR");

        var response = post("/api/campaigns/999999/runs", token, adaptiveBody());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"CAMPAIGN_NOT_FOUND\"");
    }

    @Test
    void missingRunReturns404WithRunNotFoundCode() throws Exception {
        var token = token(1L, "EDITOR");

        var response = get("/api/runs/999999/execution-decision", token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"RUN_NOT_FOUND\"");
    }

    private long createCampaign(String token) throws Exception {
        var response = post("/api/campaigns", token, """
                {"name":"PulseInk 秋招发布","objective":"向 Java 后端开发者介绍 PulseInk",
                 "audience":"关注 Agent 工程化的 Java 开发者","channels":["BLOG","SOCIAL"],
                 "constraints":["事实性结论必须给出引用"]}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        var location = response.headers().firstValue("Location").orElse("");
        var matcher = CAMPAIGN_LOCATION_ID.matcher(location);
        if (!matcher.find()) {
            throw new AssertionError("Location header does not contain a campaign id: " + location);
        }
        return Long.parseLong(matcher.group(1));
    }

    private long startRun(String token, long campaignId) throws Exception {
        var response = post("/api/campaigns/" + campaignId + "/runs", token, adaptiveBody());
        assertThat(response.statusCode()).isEqualTo(201);
        return extractRunId(response.headers().firstValue("Location").orElse(""));
    }

    private long extractRunId(String location) {
        var matcher = RUN_LOCATION_ID.matcher(location);
        if (!matcher.find()) {
            throw new AssertionError("Location header does not contain a run id: " + location);
        }
        return Long.parseLong(matcher.group(1));
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

    private HttpResponse<String> postAnonymous(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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

    private String adaptiveBody() {
        return """
                {"requestedPolicy":"ADAPTIVE","taskProperties":{
                  "decomposability":0.8,"channelCount":2,"sourceDiversity":2,
                  "parallelResearchBranches":2,"sequentialDependency":0.4,
                  "factualRisk":0.8,"toolBreadth":3,"latencyBudgetMs":20000}}
                """;
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
