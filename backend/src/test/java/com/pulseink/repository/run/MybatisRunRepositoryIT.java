package com.pulseink.repository.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake"
})
@Transactional
class MybatisRunRepositoryIT {

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

    @Autowired
    private MybatisRunRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertAssignsIdAndPersistsEveryDecisionField() {
        var campaignId = insertCampaign();
        var run = CampaignRun.create(campaignId, ExecutionPolicy.ADAPTIVE);
        run.select(decision());

        var persisted = repository.insert(run);

        assertThat(persisted.id()).isPositive();
        assertThat(persisted.campaignId()).isEqualTo(campaignId);
        assertThat(persisted.requestedPolicy()).isEqualTo(ExecutionPolicy.ADAPTIVE);
        assertThat(persisted.state()).isEqualTo(RunState.CREATED);
        assertThat(persisted.selectedMode()).isEqualTo(ExecutionMode.ORCHESTRATED);
        assertThat(persisted.selectorPolicyVersion()).isEqualTo("selector-v1");
        assertThat(persisted.selectionReasonCodes())
                .containsExactly("DECOMPOSABLE_OR_HIGH_RISK");
        assertThat(persisted.selectionFeatureSnapshot())
                .containsEntry("channelCount", 2)
                .containsEntry("factualRisk", 0.8);
        assertThat(persisted.estimatedTokenBudget()).isEqualTo(20_000L);
        assertThat(persisted.createdAt()).isNotNull();
        assertThat(persisted.updatedAt()).isNotNull();
    }

    @Test
    void decisionFieldsSurviveARoundTrip() {
        var campaignId = insertCampaign();
        var run = CampaignRun.create(campaignId, ExecutionPolicy.REACT);
        run.select(new ExecutionDecision(
                ExecutionMode.REACT,
                "selector-v1",
                List.of("MANUAL_POLICY_OVERRIDE"),
                Map.of("channelCount", 1, "factualRisk", 0.3),
                8_000L));

        var persisted = repository.insert(run);
        var reloaded = repository.findById(persisted.id()).orElseThrow();

        assertThat(reloaded.state()).isEqualTo(RunState.CREATED);
        assertThat(reloaded.requestedPolicy()).isEqualTo(ExecutionPolicy.REACT);
        assertThat(reloaded.selectedMode()).isEqualTo(ExecutionMode.REACT);
        assertThat(reloaded.selectorPolicyVersion()).isEqualTo("selector-v1");
        assertThat(reloaded.selectionReasonCodes())
                .containsExactly("MANUAL_POLICY_OVERRIDE");
        assertThat(reloaded.selectionFeatureSnapshot())
                .containsEntry("channelCount", 1)
                .containsEntry("factualRisk", 0.3);
        assertThat(reloaded.estimatedTokenBudget()).isEqualTo(8_000L);
    }

    @Test
    void findByIdReturnsEmptyForAnAbsentPositiveId() {
        assertThat(repository.findById(99_999_999L)).isEmpty();
    }

    @Test
    void unknownStoredValuesFailWithDescriptivePersistenceException() {
        var campaignId = insertCampaign();
        var runId = insertRawRun(campaignId, "ADAPTIVE", "BOGUS");

        assertThatThrownBy(() -> repository.findById(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void unknownStoredPolicyFailsWithDescriptivePersistenceException() {
        var campaignId = insertCampaign();
        var runId = insertRawRun(campaignId, "BOGUS", null);

        assertThatThrownBy(() -> repository.findById(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void casUpdateSucceedsWithExpectedVersionAndFailsWhenStale() {
        var campaignId = insertCampaign();
        var run = CampaignRun.create(campaignId, ExecutionPolicy.REACT);
        run.select(new ExecutionDecision(
                ExecutionMode.DIRECT, "selector-v1", List.of("MANUAL_POLICY_OVERRIDE"),
                Map.of(), 8_000L));
        var persisted = repository.insert(run);

        persisted.beginExecution(java.time.Instant.now());
        repository.update(persisted);

        var reloaded = repository.findById(persisted.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(RunState.RUNNING);
        assertThat(reloaded.version()).isEqualTo(1L);
        assertThat(reloaded.startedAt()).isNotNull();

        var staleCopy = CampaignRun.materialize(
                reloaded.id(), reloaded.campaignId(), reloaded.requestedPolicy(),
                reloaded.state(), reloaded.selectedMode(), reloaded.selectorPolicyVersion(),
                reloaded.selectionReasonCodes(), reloaded.selectionFeatureSnapshot(),
                reloaded.estimatedTokenBudget(), reloaded.failureReason(), 0L,
                reloaded.startedAt(), reloaded.completedAt(),
                reloaded.createdAt(), reloaded.updatedAt());
        assertThatThrownBy(() -> repository.update(staleCopy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale run update");
    }

    @Test
    void stateChangeAndStartedAtSurviveRoundTrip() {
        var campaignId = insertCampaign();
        var run = CampaignRun.create(campaignId, ExecutionPolicy.REACT);
        run.select(new ExecutionDecision(
                ExecutionMode.REACT, "selector-v1", List.of("MANUAL_POLICY_OVERRIDE"),
                Map.of(), 8_000L));
        var persisted = repository.insert(run);
        var startedAt = java.time.Instant.now();
        persisted.beginExecution(startedAt);
        repository.update(persisted);

        var reloaded = repository.findById(persisted.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(RunState.RUNNING);
        assertThat(reloaded.startedAt())
                .isCloseTo(startedAt, org.assertj.core.api.Assertions.within(
                        2, java.time.temporal.ChronoUnit.MICROS));
    }

    private long insertCampaign() {
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, 0)
                """, "Campaign", "objective", "audience", "[\"BLOG\"]", "[]");
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRawRun(long campaignId, String requestedPolicy, String selectedMode) {
        jdbcTemplate.update("""
                INSERT INTO campaign_run (campaign_id, requested_policy, selected_mode, state)
                VALUES (?, ?, ?, 'CREATED')
                """, campaignId, requestedPolicy, selectedMode);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static ExecutionDecision decision() {
        return new ExecutionDecision(
                ExecutionMode.ORCHESTRATED,
                "selector-v1",
                List.of("DECOMPOSABLE_OR_HIGH_RISK"),
                Map.of("channelCount", 2, "factualRisk", 0.8),
                20_000L);
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
