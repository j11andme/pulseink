package com.pulseink.service.publishing;

import static com.pulseink.service.publishing.PublicationErrorCode.CONTENT_NOT_APPROVED;
import static com.pulseink.service.publishing.PublicationErrorCode.CONTENT_NOT_LATEST;
import static com.pulseink.service.publishing.PublicationErrorCode.CONTENT_FORMAT_INVALID;
import static com.pulseink.service.publishing.PublicationErrorCode.PUBLICATION_CONFLICT;
import static com.pulseink.service.publishing.PublicationErrorCode.PUBLICATION_NOT_FOUND;
import static com.pulseink.service.publishing.PublicationErrorCode.VALIDATION_ERROR;

import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.publication.Publication;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publication use cases. The first valid request creates a PENDING publication with a fresh
 * UUID idempotency key and flips a WAITING_APPROVAL run into PUBLISHING, all in one short
 * transaction; replays return the original row unchanged.
 */
public final class PublicationService implements PublishContentUseCase, QueryPublicationUseCase,
        ReturnPublicationToEditingUseCase {

    private static final Set<RunState> PUBLISHABLE_RUN_STATES =
            Set.of(RunState.WAITING_APPROVAL, RunState.PUBLISHING, RunState.COMPLETED);

    private final PublicationRepository publications;
    private final ContentWorkflowRepository content;
    private final RunRepository runs;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PublicationService(PublicationRepository publications,
                              ContentWorkflowRepository content,
                              RunRepository runs,
                              TransactionTemplate transactions,
                              Clock clock) {
        this.publications = Objects.requireNonNull(publications);
        this.content = Objects.requireNonNull(content);
        this.runs = Objects.requireNonNull(runs);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Publication publish(PublishContentUseCase.Command command) {
        Objects.requireNonNull(command, "command must not be null");
        validate(command);
        return transactions.execute(status -> {
            ContentItem item = content.findById(command.contentId()).orElseThrow(() ->
                    new PublicationException(CONTENT_NOT_APPROVED,
                            "content " + command.contentId() + " was not found"));
            ContentVersion version = item.versions().stream()
                    .filter(candidate -> candidate.id() == command.contentVersionId())
                    .findFirst().orElseThrow(() -> new PublicationException(CONTENT_NOT_LATEST,
                            "content version " + command.contentVersionId()
                                    + " does not belong to content " + command.contentId()));
            validateContentFormat(version);
            var approval = item.approvals().stream()
                    .filter(candidate -> candidate.contentVersionId() == version.id())
                    .findFirst().orElseThrow(() -> new PublicationException(CONTENT_NOT_APPROVED,
                            "content version " + version.id() + " is not approved"));
            var run = runs.findById(item.runId()).orElseThrow(() ->
                    new PublicationException(PUBLICATION_CONFLICT,
                            "run " + item.runId() + " was not found"));
            if (!PUBLISHABLE_RUN_STATES.contains(run.state())) {
                throw new PublicationException(PUBLICATION_CONFLICT,
                        "run " + run.id() + " is not in a publishable state");
            }

            var pending = Publication.pending(run.id(), version.id(), approval.id(),
                    command.actorUserId(), command.channel(), UUID.randomUUID(),
                    clock.instant());
            Publication created = publications.createOrGet(pending);
            boolean firstRequest = created.idempotencyKey().equals(pending.idempotencyKey());
            if (firstRequest && run.state() == RunState.WAITING_APPROVAL) {
                run.beginPublishing();
                try {
                    runs.update(run);
                } catch (IllegalStateException stale) {
                    throw new PublicationException(PUBLICATION_CONFLICT,
                            "run " + run.id() + " changed concurrently");
                }
            }
            return created;
        });
    }

    @Override
    public Publication get(long publicationId) {
        if (publicationId <= 0) {
            throw new PublicationException(VALIDATION_ERROR, "publication id must be positive");
        }
        return publications.findById(publicationId).orElseThrow(() ->
                new PublicationException(PUBLICATION_NOT_FOUND,
                        "publication " + publicationId + " was not found"));
    }

    @Override
    public List<Publication> findByRunId(long runId) {
        if (runId <= 0) {
            throw new PublicationException(VALIDATION_ERROR, "run id must be positive");
        }
        return publications.findByRunId(runId);
    }

    @Override
    public void returnToEditing(long publicationId) {
        if (publicationId <= 0) {
            throw new PublicationException(VALIDATION_ERROR,
                    "publication id must be positive");
        }
        transactions.executeWithoutResult(status -> {
            var publication = publications.findById(publicationId).orElseThrow(() ->
                    new PublicationException(PUBLICATION_NOT_FOUND,
                            "publication " + publicationId + " was not found"));
            if (publication.status()
                    != com.pulseink.domain.publication.PublicationStatus.FAILED) {
                throw new PublicationException(PUBLICATION_CONFLICT,
                        "only a failed publication can return to editing");
            }
            var run = runs.findById(publication.runId()).orElseThrow(() ->
                    new PublicationException(PUBLICATION_CONFLICT,
                            "run " + publication.runId() + " was not found"));
            if (run.state() == RunState.WAITING_HUMAN) {
                return;
            }
            if (run.state() != RunState.PUBLISHING) {
                throw new PublicationException(PUBLICATION_CONFLICT,
                        "run " + run.id() + " cannot return to editing from " + run.state());
            }
            run.waitForPublicationCorrection();
            runs.update(run);
        });
    }

    private void validate(PublishContentUseCase.Command command) {
        if (command.contentId() <= 0 || command.contentVersionId() <= 0
                || command.channel() == null || command.actorUserId() <= 0) {
            throw new PublicationException(VALIDATION_ERROR,
                    "publish request is invalid");
        }
    }

    private static void validateContentFormat(ContentVersion version) {
        var missingFields = List.of("title", "body").stream()
                .filter(field -> {
                    Object value = version.content().get(field);
                    return !(value instanceof String text) || text.isBlank();
                })
                .toList();
        if (!missingFields.isEmpty()) {
            throw new PublicationException(CONTENT_FORMAT_INVALID,
                    "所选 v" + version.versionNo() + " 不符合发布格式，缺少 "
                            + String.join("、", missingFields)
                            + "；请选择其他版本尝试发布。");
        }
    }
}
