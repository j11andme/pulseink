package com.pulseink.client.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.service.evaluation.EvaluationCase;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Strict filesystem adapter for the versioned, repository-owned PulseInk-Eval dataset. */
public final class FileSystemEvaluationCaseCatalog implements EvaluationCaseCatalog {

    private final Path root;
    private final ObjectMapper mapper;
    private volatile List<EvaluationCase> cached;

    public FileSystemEvaluationCaseCatalog(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public List<EvaluationCase> all() {
        var snapshot = cached;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    private List<EvaluationCase> load() {
        Path cases = root.resolve("cases");
        if (!Files.isDirectory(cases)) {
            throw new IllegalStateException("evaluation cases directory not found: " + cases);
        }
        try (var paths = Files.list(cases)) {
            var loaded = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readCase)
                    .toList();
            var ids = new HashSet<String>();
            for (var testCase : loaded) {
                if (!ids.add(testCase.caseId())) {
                    throw new IllegalArgumentException("duplicate caseId: " + testCase.caseId());
                }
                requireReference(testCase.knowledgeSnapshot());
                requireReference(testCase.searchFixtures());
                requireReference(testCase.rubric());
                validateScenario(testCase);
            }
            return List.copyOf(loaded);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load evaluation cases", ex);
        }
    }

    private EvaluationCase readCase(Path path) {
        try {
            var testCase = mapper.readValue(path.toFile(), EvaluationCase.class);
            String expectedName = testCase.caseId() + ".json";
            if (!path.getFileName().toString().equals(expectedName)) {
                throw new IllegalArgumentException("case filename must match caseId: " + path);
            }
            return testCase;
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid evaluation case: " + path.getFileName(), ex);
        }
    }

    private void requireReference(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("invalid evaluation fixture reference: " + relative);
        }
    }

    private void validateScenario(EvaluationCase testCase) {
        requireFailureTool(testCase, "MISSING_EVIDENCE", "builtin.knowledge_search");
        requireFailureTool(testCase, "TOOL_TIMEOUT", "builtin.knowledge_search");
        requireFailureTool(testCase, "PROMPT_INJECTION", "builtin.knowledge_search");
        requireFailureTool(testCase, "SSRF", "builtin.http_fetch");
        requireFailureTool(testCase, "REVIEW_LIMIT", "builtin.deterministic_validate");
        if ((testCase.failureInjection().contains("PROMPT_INJECTION")
                || testCase.failureInjection().contains("TOOL_TIMEOUT")
                || testCase.failureInjection().contains("SSRF"))
                && testCase.applicablePolicies().contains(
                        com.pulseink.domain.execution.ExecutionPolicy.DIRECT)) {
            throw new IllegalArgumentException(
                    "tool scenario cannot apply to DIRECT: " + testCase.caseId());
        }
        if (testCase.failureInjection().contains("REVIEW_LIMIT")
                && testCase.applicablePolicies().stream().anyMatch(policy ->
                        policy != com.pulseink.domain.execution.ExecutionPolicy.ORCHESTRATED
                                && policy != com.pulseink.domain.execution.ExecutionPolicy.ADAPTIVE)) {
            throw new IllegalArgumentException(
                    "review-limit scenario requires coordinated policies: " + testCase.caseId());
        }
        validateEvidenceIds(testCase);
    }

    private static void requireFailureTool(EvaluationCase testCase,
                                           String injection,
                                           String tool) {
        if (testCase.failureInjection().contains(injection)
                && !testCase.allowedTools().contains(tool)) {
            throw new IllegalArgumentException(
                    injection + " requires " + tool + ": " + testCase.caseId());
        }
    }

    private void validateEvidenceIds(EvaluationCase testCase) {
        try {
            var snapshot = mapper.readTree(
                    root.resolve(testCase.knowledgeSnapshot()).normalize().toFile());
            var ids = new HashSet<String>();
            snapshot.path("chunks").forEach(chunk -> ids.add(chunk.path("chunkId").asText()));
            boolean missingExpected = testCase.failureInjection().contains("MISSING_EVIDENCE");
            boolean anyMissing = testCase.relevantChunkIds().stream().anyMatch(id -> !ids.contains(id));
            if (missingExpected != anyMissing) {
                throw new IllegalArgumentException(
                        "relevant evidence does not match scenario: " + testCase.caseId());
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "invalid knowledge snapshot for case: " + testCase.caseId(), ex);
        }
    }

    public Path root() {
        return root;
    }
}
