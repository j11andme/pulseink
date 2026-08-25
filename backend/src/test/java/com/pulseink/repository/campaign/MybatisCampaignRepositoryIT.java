package com.pulseink.repository.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class MybatisCampaignRepositoryIT {

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
    private MybatisCampaignRepository repository;

    @Autowired
    private CampaignMapper mapper;

    @Test
    void insertAssignsIdDraftStatusVersionAndTimestamps() {
        var draft = draft("Insert Audit", 7L);

        var persisted = repository.insert(draft);

        assertThat(persisted.id()).isPositive();
        assertThat(persisted.name()).isEqualTo("Insert Audit");
        assertThat(persisted.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(persisted.createdBy()).isEqualTo(7L);
        assertThat(persisted.version()).isZero();
        assertThat(persisted.createdAt()).isPresent();
        assertThat(persisted.updatedAt()).isPresent();
    }

    @Test
    void channelsAndConstraintsSurviveARoundTripInOriginalOrder() {
        var draft = new Campaign(
                0L,
                "Round Trip Order",
                new CampaignBrief(
                        "向 Java 开发者介绍 PulseInk",
                        "Java 后端工程师",
                        List.of(CampaignChannel.SOCIAL, CampaignChannel.BLOG, CampaignChannel.SHORT_VIDEO),
                        List.of("事实性结论必须给出引用", "避免夸大效果", "语气克制")),
                CampaignStatus.DRAFT,
                11L,
                0L,
                Optional.empty(),
                Optional.empty());

        var persisted = repository.insert(draft);
        var reloaded = repository.findById(persisted.id()).orElseThrow();

        assertThat(reloaded.brief().channels())
                .containsExactly(
                        CampaignChannel.SOCIAL,
                        CampaignChannel.BLOG,
                        CampaignChannel.SHORT_VIDEO);
        assertThat(reloaded.brief().constraints())
                .containsExactly(
                        "事实性结论必须给出引用",
                        "避免夸大效果",
                        "语气克制");
    }

    @Test
    void findByIdReturnsEmptyForAnAbsentPositiveId() {
        assertThat(repository.findById(99_999_999L)).isEmpty();
    }

    @Test
    void findPageReportsSizeAndTotalMetadata() {
        repository.insert(draft("Page A", 1L));
        repository.insert(draft("Page B", 2L));
        repository.insert(draft("Page C", 3L));

        CampaignPage page = repository.findPage(0, 2);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void rowsWithEqualTimestampsAreOrderedByDescendingId() {
        var first = repository.insert(draft("Tie First", 4L));
        var second = repository.insert(draft("Tie Second", 5L));
        var third = repository.insert(draft("Tie Third", 6L));

        var equalTimestamp = Instant.parse("2026-08-04T12:00:00.000000Z");
        equalizeCreatedAt(first.id(), equalTimestamp);
        equalizeCreatedAt(second.id(), equalTimestamp);
        equalizeCreatedAt(third.id(), equalTimestamp);

        CampaignPage page = repository.findPage(0, 20);

        assertThat(page.items()).extracting(Campaign::id)
                .containsExactly(third.id(), second.id(), first.id());
    }

    private Campaign draft(String name, long createdBy) {
        return new Campaign(
                0L,
                name,
                new CampaignBrief(
                        "objective",
                        "audience",
                        List.of(CampaignChannel.BLOG),
                        List.of("constraint")),
                CampaignStatus.DRAFT,
                createdBy,
                0L,
                Optional.empty(),
                Optional.empty());
    }

    private void equalizeCreatedAt(long id, Instant timestamp) {
        mapper.update(null,
                Wrappers.<CampaignEntity>lambdaUpdate()
                        .set(CampaignEntity::getCreatedAt, timestamp)
                        .eq(CampaignEntity::getId, id));
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
