package com.pulseink.controller.feedback;

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
class FeedbackControllerIT {

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

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"content_metric_daily", "feedback_inbox", "publication",
                "approval_record", "content_version", "content_item", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("INSERT INTO campaign(name,objective,audience,channels_json,constraints_json,status,created_by,version) VALUES ('c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)");
        long campaign = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO campaign_run(campaign_id,requested_policy,state,version) VALUES (?,'DIRECT','PUBLISHING',0)", campaign);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_item(run_id,task_id,current_version_no,version) VALUES (?,'task',1,0)", runId);
        long item = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_version(content_item_id,version_no,content_json,source_refs_json,origin) VALUES (?,1,'{}','[]','HUMAN')", item);
        long version = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO approval_record(content_version_id,actor_id,comment_text) VALUES (?,1,'ok')", version);
        long approval = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO publication(run_id,content_version_id,approval_record_id,requested_by,
                                        channel,idempotency_key,status,next_attempt_at,version)
                VALUES (?,?,?,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0)
                """, runId, version, approval, UUID.randomUUID().toString());
        long first = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO publication(run_id,content_version_id,approval_record_id,requested_by,
                                        channel,idempotency_key,status,next_attempt_at,version)
                VALUES (?,?,?,1,'SOCIAL',?,'PUBLISHED',UTC_TIMESTAMP(6),0)
                """, runId, version, approval, UUID.randomUUID().toString());
        long second = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (?, '2026-08-13', 100, 12, 4),
                       (?, '2026-08-13', 50, 6, 2),
                       (?, '2026-08-14', 20, 1, 0)
                """, first, second, first);
    }

    @Test
    void metricsAreReturnedOrderedByPublicationAndDate() throws Exception {
        var response = get("/api/runs/" + runId + "/metrics", token(2L, "VIEWER"));

        assertThat(response.statusCode()).isEqualTo(200);
        var metrics = objectMapper.readTree(response.body());
        assertThat(metrics).hasSize(3);
        assertThat(metrics.get(0).get("publicationId").asLong())
                .isLessThanOrEqualTo(metrics.get(1).get("publicationId").asLong());
        assertThat(metrics.get(1).get("publicationId").asLong())
                .isLessThanOrEqualTo(metrics.get(2).get("publicationId").asLong());
        assertThat(metrics.get(0).get("views").asLong()).isEqualTo(100);
        assertThat(metrics.get(0).get("clicks").asLong()).isEqualTo(12);
        assertThat(metrics.get(0).get("likes").asLong()).isEqualTo(4);
        assertThat(metrics.get(0).get("metricDate").asText()).isEqualTo("2026-08-13");
    }

    @Test
    void anonymousMetricsReadIsUnauthorized() throws Exception {
        assertThat(get("/api/runs/" + runId + "/metrics", null).statusCode())
                .isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(long uid, String role) {
        var claims = JwtClaimsSet.builder().subject("test-" + uid)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("uid", uid).claim("roles", List.of(role)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
