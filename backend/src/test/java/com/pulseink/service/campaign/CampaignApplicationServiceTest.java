package com.pulseink.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.service.campaign.CreateCampaignUseCase.CreateCampaignCommand;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignApplicationServiceTest {

    @Test
    void validEditorCommandCreatesADraftAndPassesActorIdToRepository() {
        var repository = new FakeCampaignRepository();
        var service = new CampaignApplicationService(repository);

        var created = service.create(command("PulseInk 秋招发布"), 1L);

        assertThat(created.id()).isEqualTo(1L);
        assertThat(created.name()).isEqualTo("PulseInk 秋招发布");
        assertThat(created.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.createdBy()).isEqualTo(1L);
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isPresent();
        assertThat(created.updatedAt()).isPresent();

        var captured = repository.capturedDraft;
        assertThat(captured.id()).isZero();
        assertThat(captured.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(captured.createdBy()).isEqualTo(1L);
        assertThat(captured.version()).isZero();
        assertThat(captured.createdAt()).isEmpty();
        assertThat(captured.updatedAt()).isEmpty();
        assertThat(captured.brief().channels())
                .containsExactly(CampaignChannel.BLOG, CampaignChannel.SOCIAL);
    }

    @Test
    void repositoryResultSuppliesIdentityAndTimestamps() {
        var repository = new FakeCampaignRepository();
        repository.nextId = 42L;
        repository.nextVersion = 7L;
        repository.nextCreatedAt = Instant.parse("2026-08-04T12:00:00Z");
        repository.nextUpdatedAt = Instant.parse("2026-08-04T12:30:00Z");
        var service = new CampaignApplicationService(repository);

        var created = service.create(command("Persisted"), 5L);

        assertThat(created.id()).isEqualTo(42L);
        assertThat(created.version()).isEqualTo(7L);
        assertThat(created.createdAt()).contains(Instant.parse("2026-08-04T12:00:00Z"));
        assertThat(created.updatedAt()).contains(Instant.parse("2026-08-04T12:30:00Z"));
    }

    @Test
    void blankAndOversizedFieldsAreRejectedBeforeRepositoryAccess() {
        var repository = new FakeCampaignRepository();
        var service = new CampaignApplicationService(repository);
        var oversizedObjective = "x".repeat(4_001);
        var oversizedAudience = "y".repeat(2_001);
        var oversizedConstraint = "z".repeat(501);

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("  ", "objective", "audience",
                        List.of(CampaignChannel.BLOG), List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign name must not be blank");

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("x".repeat(129), "objective", "audience",
                        List.of(CampaignChannel.BLOG), List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign name must contain at most 128 characters");

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", oversizedObjective, "audience",
                        List.of(CampaignChannel.BLOG), List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign objective must contain at most 4000 characters");

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", "objective", oversizedAudience,
                        List.of(CampaignChannel.BLOG), List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign audience must contain at most 2000 characters");

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", "objective", "audience",
                        List.of(), List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", "objective", "audience",
                        List.of(
                                CampaignChannel.BLOG,
                                CampaignChannel.SOCIAL,
                                CampaignChannel.SHORT_VIDEO,
                                CampaignChannel.BLOG),
                        List.of()), 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", "objective", "audience",
                        List.of(CampaignChannel.BLOG),
                        List.of(oversizedConstraint)), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign constraints must contain at most 500 characters");

        assertThatThrownBy(() -> service.create(
                new CreateCampaignCommand("name", "objective", "audience",
                        List.of(CampaignChannel.BLOG),
                        List.of("valid", " ")), 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(command("name"), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actor user id must be positive");

        assertThat(repository.invocations).isZero();
    }

    @Test
    void nullConstraintsAreRejectedInsteadOfSilentlyDefaulted() {
        var repository = new FakeCampaignRepository();
        var service = new CampaignApplicationService(repository);
        var command = new CreateCampaignCommand(
                "Campaign",
                "Objective",
                "Audience",
                List.of(CampaignChannel.BLOG),
                null);

        assertThatThrownBy(() -> service.create(command, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign constraints must not be null");
        assertThat(repository.invocations).isZero();
    }

    @Test
    void listRejectsInvalidPageAndSizeWithoutClamping() {
        var repository = new FakeCampaignRepository();
        var service = new CampaignApplicationService(repository);

        assertThatThrownBy(() -> service.list(-1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must not be negative");
        assertThatThrownBy(() -> service.list(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be between 1 and 100");
        assertThatThrownBy(() -> service.list(0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be between 1 and 100");
    }

    @Test
    void absentPositiveIdThrowsCampaignNotFound() {
        var repository = new FakeCampaignRepository();
        var service = new CampaignApplicationService(repository);

        assertThatThrownBy(() -> service.get(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign id must be positive");
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(CampaignNotFoundException.class)
                .hasMessage("campaign 99 was not found");
    }

    @Test
    void returnedPageAndBriefCollectionsAreImmutableSnapshots() {
        var repository = new FakeCampaignRepository();
        repository.items.add(persistedCampaign(1L, "First"));
        repository.items.add(persistedCampaign(2L, "Second"));
        repository.totalElements = 2L;
        var service = new CampaignApplicationService(repository);

        var page = service.list(0, 20);

        assertThatThrownBy(() -> page.items().add(persistedCampaign(3L, "Third")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);

        var first = page.items().get(0);
        assertThatThrownBy(() -> first.brief().channels().add(CampaignChannel.SHORT_VIDEO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.brief().constraints().add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CreateCampaignCommand command(String name) {
        return new CreateCampaignCommand(
                name,
                "向 Java 后端开发者介绍 PulseInk",
                "关注 Agent 工程化的 Java 开发者",
                List.of(CampaignChannel.BLOG, CampaignChannel.SOCIAL),
                List.of("事实性结论必须给出引用", "避免夸大效果"));
    }

    private static Campaign persistedCampaign(long id, String name) {
        var brief = new CampaignBrief(
                "objective",
                "audience",
                List.of(CampaignChannel.BLOG),
                List.of("constraint one"));
        var now = Instant.parse("2026-08-04T12:00:00Z");
        return new Campaign(
                id,
                name,
                brief,
                CampaignStatus.DRAFT,
                1L,
                0L,
                Optional.of(now),
                Optional.of(now));
    }

    private static final class FakeCampaignRepository implements CampaignRepository {

        private final List<Campaign> items = new ArrayList<>();
        private long totalElements;
        private long nextId = 1L;
        private long nextVersion;
        private Instant nextCreatedAt = Instant.parse("2026-08-04T12:00:00Z");
        private Instant nextUpdatedAt = Instant.parse("2026-08-04T12:00:00Z");
        private Campaign capturedDraft;
        private int invocations;

        @Override
        public Campaign insert(Campaign draft) {
            capturedDraft = draft;
            invocations++;
            return new Campaign(
                    nextId,
                    draft.name(),
                    draft.brief(),
                    draft.status(),
                    draft.createdBy(),
                    nextVersion,
                    Optional.of(nextCreatedAt),
                    Optional.of(nextUpdatedAt));
        }

        @Override
        public CampaignPage findPage(int page, int size) {
            invocations++;
            return new CampaignPage(
                    List.copyOf(items),
                    page,
                    size,
                    totalElements,
                    items.isEmpty() ? 0 : (int) Math.ceil((double) totalElements / size));
        }

        @Override
        public Optional<Campaign> findById(long campaignId) {
            invocations++;
            return items.stream()
                    .filter(campaign -> campaign.id() == campaignId)
                    .findFirst();
        }
    }
}
