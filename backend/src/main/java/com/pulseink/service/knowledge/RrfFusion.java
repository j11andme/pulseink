package com.pulseink.service.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic reciprocal-rank fusion. Ranks start at 1, each branch counts a chunk once,
 * score is {@code 1/(constant+rank)}, and results are ordered by {@code rrfScore DESC,
 * chunkId ASC}.
 */
public final class RrfFusion {

    public List<FusedResult> fuse(List<RetrievalCandidates.Candidate> lexical,
                                  List<RetrievalCandidates.Candidate> vector,
                                  int topK, int constant) {
        Objects.requireNonNull(lexical, "lexical must not be null");
        Objects.requireNonNull(vector, "vector must not be null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (constant <= 0) {
            throw new IllegalArgumentException("constant must be positive");
        }
        var scores = new HashMap<String, Double>();
        var channels = new HashMap<String, Set<String>>();
        var documents = new HashMap<String, long[]>();
        rank(lexical, scores, channels, documents, "LEXICAL", constant);
        rank(vector, scores, channels, documents, "VECTOR", constant);

        var ordered = new ArrayList<Map.Entry<String, Double>>(scores.entrySet());
        ordered.sort(Comparator
                .<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        var result = new ArrayList<FusedResult>();
        for (int i = 0; i < Math.min(topK, ordered.size()); i++) {
            var entry = ordered.get(i);
            long[] meta = documents.get(entry.getKey());
            result.add(new FusedResult(
                    entry.getKey(),
                    meta[0],
                    (int) meta[1],
                    entry.getValue(),
                    channels.get(entry.getKey())));
        }
        return List.copyOf(result);
    }

    private static void rank(List<RetrievalCandidates.Candidate> candidates,
                             Map<String, Double> scores,
                             Map<String, Set<String>> channels,
                             Map<String, long[]> documents,
                             String channel, int constant) {
        var seen = new LinkedHashSet<String>();
        for (var candidate : candidates) {
            if (!seen.add(candidate.chunkId())) {
                continue;
            }
            int rank = seen.size();
            scores.merge(candidate.chunkId(), 1.0 / (constant + rank), Double::sum);
            channels.computeIfAbsent(candidate.chunkId(), ignored -> new LinkedHashSet<>())
                    .add(channel);
            documents.put(candidate.chunkId(),
                    new long[] {candidate.documentId(), candidate.documentVersion()});
        }
    }

    /**
     * Fused result carrying the aggregate RRF score and the channels that matched.
     */
    public record FusedResult(
            String chunkId,
            long documentId,
            int documentVersion,
            double rrfScore,
            Set<String> channels) {
    }
}
