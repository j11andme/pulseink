package com.pulseink.controller.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.service.campaign.RunEventService;
import com.pulseink.service.campaign.RunEventType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
            "pulseink.model.provider=fake"
        })
class RunEventReplayIT {

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
    private RunEventService eventService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanAndSeed() {
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
    void withoutLastEventIdReplaysOneTwoThree() throws Exception {
        var runId = seedRunAndEvents(3);
        var token = token(1L, "EDITOR");

        var lines = readSseLines("/api/runs/" + runId + "/events", token, 3 * 3);

        assertThat(lines).hasSize(9);
        assertThat(extractIds(lines)).containsExactly(1L, 2L, 3L);
        assertThat(extractNames(lines)).containsExactly(
                "execution_mode_selected", "run_state_changed", "decision_recorded");
    }

    @Test
    void lastEventIdOneReplaysOnlyTwoThree() throws Exception {
        var runId = seedRunAndEvents(3);
        var token = token(1L, "EDITOR");

        var lines = readSseLines("/api/runs/" + runId + "/events", token, 2 * 3, "1");

        assertThat(extractIds(lines)).containsExactly(2L, 3L);
    }

    @Test
    void lastEventIdAtLatestWaitsForLiveEvent() throws Exception {
        var runId = seedRunAndEvents(3);
        var token = token(1L, "EDITOR");
        var received = new CountDownLatch(1);

        var thread = new Thread(() -> {
            try {
                var lines = readSseLines("/api/runs/" + runId + "/events", token, 1 * 3, "3");
                assertThat(extractIds(lines)).containsExactly(4L);
                received.countDown();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        thread.start();
        Thread.sleep(500);

        eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                Map.of("decisionSummary", "live event"));

        assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
        thread.join();
    }

    @Test
    void concurrentAppendDuringReplayKeepsOrderOneToFour() throws Exception {
        var runId = seedRunAndEvents(3);
        var token = token(1L, "EDITOR");
        var received = new CountDownLatch(1);
        var allLines = new ArrayList<String>();

        var thread = new Thread(() -> {
            try {
                var lines = readSseLines("/api/runs/" + runId + "/events", token, 4 * 3);
                allLines.addAll(lines);
                received.countDown();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        thread.start();
        Thread.sleep(300);

        eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                Map.of("decisionSummary", "during replay"));

        assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
        thread.join();
        assertThat(extractIds(allLines)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void secondReconnectUsesItsOwnLastId() throws Exception {
        var runId = seedRunAndEvents(3);
        var token = token(1L, "EDITOR");

        var first = readSseLines("/api/runs/" + runId + "/events", token, 3 * 3);
        assertThat(extractIds(first)).containsExactly(1L, 2L, 3L);

        eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                Map.of("decisionSummary", "after first disconnect"));
        var second = readSseLines("/api/runs/" + runId + "/events", token, 1 * 3, "3");
        assertThat(extractIds(second)).containsExactly(4L);
    }

    @Test
    void sseDataCarriesExactEventVersionAndFields() throws Exception {
        var runId = seedRunAndEvents(1);
        var token = token(1L, "EDITOR");

        var lines = readSseLines("/api/runs/" + runId + "/events", token, 3);

        var dataLine = lines.stream().filter(line -> line.startsWith("data:")).findFirst()
                .orElseThrow();
        assertThat(dataLine).contains("\"eventVersion\":\"run-event-v1\"");
        assertThat(dataLine).contains("\"runId\":" + runId);
        assertThat(dataLine).contains("\"sequence\":1");
        assertThat(dataLine).contains("\"eventType\":\"EXECUTION_MODE_SELECTED\"");
    }

    @Test
    void subscriberIsRemovedAfterClientDisconnect() throws Exception {
        var runId = seedRunAndEvents(1);
        var token = token(1L, "EDITOR");
        var url = URI.create("http://localhost:" + port + "/api/runs/" + runId + "/events");
        var request = HttpRequest.newBuilder()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            in.read(new byte[1024]);
        }
        assertThat(eventService.subscriberCount(runId)).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            Thread.sleep(500);
            eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                    Map.of("decisionSummary", "flush broken stream"));
            if (eventService.subscriberCount(runId) == 0) {
                break;
            }
        }
        assertThat(eventService.subscriberCount(runId)).isZero();
    }

    @Test
    void missingRunReturns404AndInvalidLastEventIdReturns400() throws Exception {
        var token = token(1L, "EDITOR");

        var missing = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/api/runs/999999/events"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"RUN_NOT_FOUND\"");

        var runId = seedRunAndEvents(1);
        for (String bad : List.of("-1", "abc", "99999999999999999999")) {
            var response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port
                                    + "/api/runs/" + runId + "/events"))
                            .header("Authorization", "Bearer " + token)
                            .header("Last-Event-ID", bad)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("\"code\":\"INVALID_LAST_EVENT_ID\"");
        }
    }

    @Test
    void fullPipelineEventsDoNotLeakSecrets() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, 0)
                """, "Campaign", "objective", "audience", "[\"BLOG\"]", "[]");
        var campaignId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var token = token(1L, "EDITOR");
        var directRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/campaigns/" + campaignId + "/runs"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"requestedPolicy":"DIRECT","taskProperties":{
                          "decomposability":0.1,"channelCount":1,"sourceDiversity":0,
                          "parallelResearchBranches":0,"sequentialDependency":0.1,
                          "factualRisk":0.1,"toolBreadth":0,"latencyBudgetMs":8000}}
                        """))
                .build();
        var created = httpClient.send(directRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        var matcher = java.util.regex.Pattern.compile("/api/runs/(\\d+)$")
                .matcher(created.headers().firstValue("Location").orElse(""));
        assertThat(matcher.find()).isTrue();
        long newRunId = Long.parseLong(matcher.group(1));

        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var state = jdbcTemplate.queryForObject(
                    "SELECT state FROM campaign_run WHERE id = ?", String.class, newRunId);
            if ("WAITING_APPROVAL".equals(state)) {
                break;
            }
            Thread.sleep(200);
        }

        var lines = readSseLines("/api/runs/" + newRunId + "/events", token, 4 * 3);
        assertThat(String.join("\n", lines))
                .doesNotContain("Bearer")
                .doesNotContain("sk-secret")
                .doesNotContain("Authorization");
        assertThat(extractIds(lines)).containsExactly(1L, 2L, 3L, 4L);
    }

    private long seedRunAndEvents(int count) {
        var runId = seedRun(1);
        eventService.appendAndPublish(runId, RunEventType.EXECUTION_MODE_SELECTED,
                Map.of("selectedMode", "DIRECT"));
        if (count > 1) {
            eventService.appendAndPublish(runId, RunEventType.RUN_STATE_CHANGED,
                    Map.of("fromState", "CREATED", "toState", "RUNNING"));
        }
        if (count > 2) {
            eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                    Map.of("decisionSummary", "fake"));
        }
        return runId;
    }

    private long seedRun(long campaignCount) {
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, 0)
                """, "Campaign", "objective", "audience", "[\"BLOG\"]", "[]");
        var campaignId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO campaign_run
                    (campaign_id, requested_policy, selected_mode, selector_policy_version,
                     selection_reason_json, selection_feature_json, estimated_token_budget,
                     state, version)
                VALUES (?, 'DIRECT', 'DIRECT', 'selector-v1', '[]', '{}', 8000, 'CREATED', 0)
                """, campaignId);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private List<String> readSseLines(String path, String token, int lineCount)
            throws Exception {
        return readSseLines(path, token, lineCount, null);
    }

    private List<String> readSseLines(String path, String token, int lineCount,
                                      String lastEventId) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            var collected = new ArrayList<String>();
            var iterator = lines.iterator();
            while (iterator.hasNext() && collected.size() < lineCount) {
                var line = iterator.next();
                if (!line.isBlank()) {
                    collected.add(line);
                }
            }
            return List.copyOf(collected);
        }
    }

    private static List<Long> extractIds(List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith("id:"))
                .map(line -> Long.parseLong(line.substring(3).strip()))
                .toList();
    }

    private static List<String> extractNames(List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith("event:"))
                .map(line -> line.substring(6).strip())
                .toList();
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
