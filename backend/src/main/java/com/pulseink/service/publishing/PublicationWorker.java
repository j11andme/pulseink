package com.pulseink.service.publishing;

import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublicationStatus;
import com.pulseink.domain.publication.PublishReceipt;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Due-task worker for publications. Each step uses a short transaction: claim, then Channel
 * HTTP strictly outside any transaction, then a terminal mark. When every current, approved
 * ContentVersion of a run has at least one PUBLISHED publication the run completes.
 */
public final class PublicationWorker {

    private final PublicationRepository publications;
    private final ContentWorkflowRepository content;
    private final RunRepository runs;
    private final ChannelPort channel;
    private final PublicationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PublicationWorker(PublicationRepository publications,
                             ContentWorkflowRepository content,
                             RunRepository runs,
                             ChannelPort channel,
                             PublicationProperties properties,
                             TransactionTemplate transactions,
                             Clock clock) {
        this.publications = Objects.requireNonNull(publications);
        this.content = Objects.requireNonNull(content);
        this.runs = Objects.requireNonNull(runs);
        this.channel = Objects.requireNonNull(channel);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public void processBatch() {
        processBatch(clock.instant());
    }

    public void processBatch(Instant now) {
        List<Publication> due = publications.claimDue(now, properties.batchSize());
        for (Publication publication : due) {
            attempt(publication, now);
        }
    }

    private void attempt(Publication publication, Instant now) {
        try {
            PublishReceipt receipt = channel.publish(requestFor(publication));
            if (publications.markPublished(publication.id(), publication.version(), receipt)) {
                completeRunIfAllPublished(publication.runId(), now);
            }
        } catch (ChannelRejectedException rejected) {
            if (publications.markFailed(publication.id(), publication.version(),
                    rejected.code(), rejected.getMessage())) {
                returnRunForCorrection(publication.runId());
            }
        } catch (ChannelUnavailableException unavailable) {
            if (publication.attemptCount() >= properties.maxAttempts()) {
                if (publications.markFailed(publication.id(), publication.version(),
                        "RETRIES_EXHAUSTED", unavailable.getMessage())) {
                    returnRunForCorrection(publication.runId());
                }
            } else {
                publications.markRetryWait(publication.id(), publication.version(),
                        now.plus(properties.retryDelay()), "CHANNEL_UNAVAILABLE",
                        unavailable.getMessage());
            }
        }
    }

    private void returnRunForCorrection(long runId) {
        transactions.executeWithoutResult(status -> {
            var run = runs.findById(runId).orElse(null);
            if (run == null || run.state() != RunState.PUBLISHING) {
                return;
            }
            run.waitForPublicationCorrection();
            runs.update(run);
        });
    }

    private ChannelPort.PublishRequest requestFor(Publication publication) {
        var version = content.findByRunId(publication.runId()).stream()
                .map(ContentItem::versions)
                .flatMap(List::stream)
                .filter(candidate -> candidate.id() == publication.contentVersionId())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "approved content version " + publication.contentVersionId()
                                + " is no longer readable"));
        return new ChannelPort.PublishRequest(publication.id(), version.id(),
                publication.idempotencyKey(), publication.channel(),
                Map.copyOf(version.content()), List.copyOf(version.sourceRefs()));
    }

    private void completeRunIfAllPublished(long runId, Instant now) {
        transactions.executeWithoutResult(status -> {
            var run = runs.findById(runId).orElse(null);
            if (run == null || run.state() != RunState.PUBLISHING) {
                return;
            }
            List<ContentItem> items = content.findByRunId(runId);
            List<Publication> all = publications.findByRunId(runId);
            boolean everyApprovedPublished = items.stream().allMatch(item -> {
                ContentVersion current = item.versions().stream()
                        .filter(version -> version.versionNo() == item.currentVersionNo())
                        .findFirst().orElse(null);
                boolean approved = current != null && item.approvals().stream()
                        .anyMatch(approval -> approval.contentVersionId() == current.id());
                return !approved || all.stream().anyMatch(publication ->
                        publication.contentVersionId() == current.id()
                                && publication.status() == PublicationStatus.PUBLISHED);
            });
            if (everyApprovedPublished) {
                run.complete(now);
                runs.update(run);
            }
        });
    }
}
