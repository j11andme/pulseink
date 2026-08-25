package com.pulseink.controller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class RunEventSecurityIT {

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
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, 0)
                """, "Campaign", "objective", "audience", "[\"BLOG\"]", "[]");
        var campaignId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO campaign_run
                    (campaign_id, requested_policy, selected_mode, state, version)
                VALUES (?, 'DIRECT', 'DIRECT', 'CREATED', 0)
                """, campaignId);
        var runId = jdbcTemplate.queryForObject(
                "SELECT id FROM campaign_run LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO run_event (run_id, sequence_no, event_type, payload_json)
                VALUES (?, 1, 'EXECUTION_MODE_SELECTED', '{"eventVersion":"run-event-v1"}')
                """, runId);
    }

    @Test
    void anonymousSubscriptionIsUnauthorized() throws Exception {
        var response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/runs/1/events"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void viewerCanSubscribeToRunEvents() throws Exception {
        var runId = jdbcTemplate.queryForObject(
                "SELECT id FROM campaign_run LIMIT 1", Long.class);
        var viewerToken = token(3L, "VIEWER");

        var response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/api/runs/" + runId + "/events"))
                        .header("Authorization", "Bearer " + viewerToken)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        try (Stream<String> lines = response.body()) {
            assertThat(lines.findFirst()).isPresent();
        }
    }

    @Test
    void editorAndAdminCanSubscribe() throws Exception {
        var runId = jdbcTemplate.queryForObject(
                "SELECT id FROM campaign_run LIMIT 1", Long.class);
        for (String role : List.of("EDITOR", "ADMIN")) {
            var response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port
                                    + "/api/runs/" + runId + "/events"))
                            .header("Authorization", "Bearer " + token(1L, role))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode()).isEqualTo(200);
            try (Stream<String> lines = response.body()) {
                assertThat(lines.findFirst()).isPresent();
            }
        }
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
