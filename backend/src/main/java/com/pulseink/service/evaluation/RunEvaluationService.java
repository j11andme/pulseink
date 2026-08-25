package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public final class RunEvaluationService implements RunEvaluationUseCase {

    public static final String DATASET_VERSION = "pulseink-eval-v1";
    public static final String CUSTOM_DATASET_VERSION = "user-custom-v1";
    public static final String SCORER_VERSION = "scorer-v2-trajectory";

    private final EvaluationCaseCatalog catalog;
    private final EvaluationPolicyExecutor executor;
    private final EvaluationScorer scorer;
    private final EvaluationJudge judge;
    private final EvaluationReportWriter reportWriter;
    private final Clock clock;
    private final AtomicReference<EvaluationReport> latest = new AtomicReference<>();

    public RunEvaluationService(EvaluationCaseCatalog catalog,
                                EvaluationPolicyExecutor executor,
                                EvaluationScorer scorer,
                                EvaluationJudge judge,
                                EvaluationReportWriter reportWriter) {
        this(catalog, executor, scorer, judge, reportWriter, Clock.systemUTC());
    }

    RunEvaluationService(EvaluationCaseCatalog catalog,
                         EvaluationPolicyExecutor executor,
                         EvaluationScorer scorer,
                         EvaluationJudge judge,
                         EvaluationReportWriter reportWriter,
                         Clock clock) {
        this.catalog = catalog;
        this.executor = executor;
        this.scorer = scorer;
        this.judge = judge;
        this.reportWriter = reportWriter;
        this.clock = clock;
    }

    @Override
    public EvaluationReport run(EvaluationRequest request) {
        if (request.suite() == EvaluationSuite.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM evaluations require the custom endpoint");
        }
        List<EvaluationCase> cases = request.suite() == EvaluationSuite.FULL
                ? catalog.all() : catalog.smokeCases();
        int repetitions = request.suite() == EvaluationSuite.STABILITY ? 3 : 1;
        var recorded = executePolicies(cases, request.policies(), repetitions);
        var deterministicScores = deterministicScores(recorded);
        var judged = judgeEligiblePairs(recorded, deterministicScores, request.judgeEnabled());
        var results = scoreExecutions(recorded, judged);
        return createReport(request.suite(), repetitions, results,
                executor.runtimeDescriptor(), DATASET_VERSION, "");
    }

    @Override
    public EvaluationReport runCustom(CustomEvaluationRequest request) {
        var testCase = customCase(request);
        var recorded = executePolicies(List.of(testCase), request.policies(), 1);
        var deterministicScores = deterministicScores(recorded);
        var judged = new HashMap<ExecutionKey, JudgeScore>();
        for (var item : recorded) {
            var deterministic = deterministicScores.get(item.key());
            if (deterministic.hardRulesPassed()
                    && !item.execution().candidateText().isBlank()) {
                judged.put(item.key(), judge.scoreAgainstReference(
                        testCase, item.execution(), request.expectedResult()));
            }
        }
        var results = recorded.stream().map(item -> {
            var semantic = judged.getOrDefault(item.key(),
                    JudgeScore.notRun(rubricVersion(testCase)));
            return new EvaluationRunResult(item.repetition(), item.execution(),
                    scorer.score(testCase, item.execution(), semantic), semantic);
        }).toList();
        var metadata = new CustomEvaluationCase(
                request.task(), request.expectedResult(), request.audience(),
                request.channel(), request.constraints());
        return createReport(EvaluationSuite.CUSTOM, 1, results,
                executor.runtimeDescriptor(), CUSTOM_DATASET_VERSION, "", metadata);
    }

    /** Re-applies current deterministic scoring to saved real trajectories without model calls. */
    public EvaluationReport regrade(EvaluationReport source) {
        var casesById = catalog.all().stream().collect(Collectors.toMap(
                EvaluationCase::caseId, java.util.function.Function.identity()));
        var recorded = source.executions().stream().map(result -> {
            var testCase = casesById.get(result.caseId());
            if (testCase == null) {
                throw new IllegalArgumentException(
                        "source report references unknown case: " + result.caseId());
            }
            return new RecordedExecution(result.repetition(), testCase, result.execution());
        }).toList();
        var deterministicScores = deterministicScores(recorded);
        var preservedJudges = preservedEligibleJudges(
                source.executions(), recorded, deterministicScores);
        var results = scoreExecutions(recorded, preservedJudges);
        return createReport(source.suite(), source.repetitions(), results,
                source.runtime(), source.datasetVersion(), source.reportId());
    }

    private EvaluationReport createReport(EvaluationSuite suite,
                                          int repetitions,
                                          List<EvaluationRunResult> results,
                                          EvaluationRuntimeDescriptor runtime,
                                          String datasetVersion,
                                          String replayedFrom) {
        return createReport(suite, repetitions, results, runtime, datasetVersion,
                replayedFrom, null);
    }

    private EvaluationReport createReport(EvaluationSuite suite,
                                          int repetitions,
                                          List<EvaluationRunResult> results,
                                          EvaluationRuntimeDescriptor runtime,
                                          String datasetVersion,
                                          String replayedFrom,
                                          CustomEvaluationCase customCase) {
        int scoredSamples = (int) results.stream()
                .filter(result -> result.score().status() == EvaluationSampleStatus.SCORED).count();
        int errorSamples = results.size() - scoredSamples;
        int judgeUnscoredSamples = (int) results.stream()
                .filter(result -> result.judge().status() == EvaluationJudgeStatus.UNSCORED).count();
        var generatedAt = clock.instant();
        var report = new EvaluationReport(
                "eval-" + generatedAt.toEpochMilli() + "-"
                        + UUID.randomUUID().toString().substring(0, 8),
                generatedAt, suite, repetitions, results, summarize(results),
                comparisons(results), modeDistribution(results),
                scoredSamples, errorSamples, judgeUnscoredSamples,
                results.isEmpty() ? 0 : errorSamples / (double) results.size(),
                false,
                runtime, replayedFrom,
                datasetVersion, SCORER_VERSION, customCase);
        reportWriter.write(report);
        latest.set(report);
        return report;
    }

    @Override
    public Optional<EvaluationReport> latest() {
        return Optional.ofNullable(latest.get());
    }

    private List<RecordedExecution> executePolicies(List<EvaluationCase> cases,
                                                    List<ExecutionPolicy> policies,
                                                    int repetitions) {
        var recorded = new ArrayList<RecordedExecution>();
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (var testCase : cases) {
                for (var policy : policies) {
                    if (!testCase.applicablePolicies().contains(policy)) {
                        continue;
                    }
                    recorded.add(new RecordedExecution(
                            repetition, testCase, executor.execute(testCase, policy)));
                }
            }
        }
        return List.copyOf(recorded);
    }

    private Map<ExecutionKey, EvaluationScore> deterministicScores(
            List<RecordedExecution> recorded) {
        var scores = new HashMap<ExecutionKey, EvaluationScore>();
        for (var item : recorded) {
            scores.put(item.key(), scorer.score(item.testCase(), item.execution(),
                    JudgeScore.notRun(rubricVersion(item.testCase()))));
        }
        return scores;
    }

    private Map<CaseRepetitionKey, JudgeScore> judgeEligiblePairs(
            List<RecordedExecution> recorded,
            Map<ExecutionKey, EvaluationScore> deterministicScores,
            boolean judgeEnabled) {
        var judged = new HashMap<CaseRepetitionKey, JudgeScore>();
        if (!judgeEnabled) {
            return judged;
        }
        var grouped = recorded.stream().collect(Collectors.groupingBy(
                RecordedExecution::caseRepetitionKey));
        for (var entry : grouped.entrySet()) {
            var react = find(entry.getValue(), ExecutionPolicy.REACT);
            var orchestrated = find(entry.getValue(), ExecutionPolicy.ORCHESTRATED);
            if (react == null || orchestrated == null) {
                continue;
            }
            if (!deterministicScores.get(react.key()).hardRulesPassed()
                    || !deterministicScores.get(orchestrated.key()).hardRulesPassed()
                    || react.execution().candidateText().isBlank()
                    || orchestrated.execution().candidateText().isBlank()) {
                continue;
            }
            judged.put(entry.getKey(), judge.compare(
                    react.testCase(), react.execution(), orchestrated.execution()));
        }
        return judged;
    }

    private Map<CaseRepetitionKey, JudgeScore> preservedEligibleJudges(
            List<EvaluationRunResult> sourceResults,
            List<RecordedExecution> recorded,
            Map<ExecutionKey, EvaluationScore> deterministicScores) {
        var judged = new HashMap<CaseRepetitionKey, JudgeScore>();
        var grouped = recorded.stream().collect(Collectors.groupingBy(
                RecordedExecution::caseRepetitionKey));
        for (var entry : grouped.entrySet()) {
            var react = find(entry.getValue(), ExecutionPolicy.REACT);
            var orchestrated = find(entry.getValue(), ExecutionPolicy.ORCHESTRATED);
            if (react == null || orchestrated == null
                    || !deterministicScores.get(react.key()).hardRulesPassed()
                    || !deterministicScores.get(orchestrated.key()).hardRulesPassed()
                    || react.execution().candidateText().isBlank()
                    || orchestrated.execution().candidateText().isBlank()) {
                continue;
            }
            sourceResults.stream()
                    .filter(result -> result.caseId().equals(entry.getKey().caseId())
                            && result.repetition() == entry.getKey().repetition()
                            && result.policy() == ExecutionPolicy.REACT)
                    .map(EvaluationRunResult::judge)
                    .findFirst()
                    .ifPresent(value -> judged.put(entry.getKey(), value));
        }
        return judged;
    }

    private List<EvaluationRunResult> scoreExecutions(
            List<RecordedExecution> recorded,
            Map<CaseRepetitionKey, JudgeScore> judged) {
        return recorded.stream().map(item -> {
            var pairJudge = judged.getOrDefault(item.caseRepetitionKey(),
                    JudgeScore.notRun(rubricVersion(item.testCase())));
            var executionJudge = switch (item.execution().policy()) {
                case REACT -> pairJudge;
                case ORCHESTRATED -> pairJudge.swappedCandidates();
                default -> JudgeScore.notRun(pairJudge.rubricVersion());
            };
            return new EvaluationRunResult(item.repetition(), item.execution(),
                    scorer.score(item.testCase(), item.execution(), executionJudge),
                    executionJudge);
        }).toList();
    }

    private List<PolicyAblationComparison> comparisons(
            List<EvaluationRunResult> results) {
        var grouped = results.stream().collect(Collectors.groupingBy(
                item -> new CaseRepetitionKey(item.caseId(), item.repetition())));
        return grouped.entrySet().stream().map(entry -> {
            var react = findResult(entry.getValue(), ExecutionPolicy.REACT);
            var orchestrated = findResult(entry.getValue(), ExecutionPolicy.ORCHESTRATED);
            if (react == null || orchestrated == null) {
                return null;
            }
            var comparison = scorer.compare(
                    react.score(), orchestrated.score(),
                    ExecutionPolicy.REACT, ExecutionPolicy.ORCHESTRATED);
            return new PolicyAblationComparison(
                    entry.getKey().caseId(), entry.getKey().repetition(),
                    comparison.comparable(),
                    comparison.qualityDelta(), comparison.coordinationOverhead(),
                    comparison.preferredPolicy(), comparison.reason());
        }).filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator
                        .comparing(PolicyAblationComparison::caseId)
                        .thenComparingInt(PolicyAblationComparison::repetition))
                .toList();
    }

    private static RecordedExecution find(List<RecordedExecution> executions,
                                          ExecutionPolicy policy) {
        return executions.stream()
                .filter(item -> item.execution().policy() == policy)
                .findFirst().orElse(null);
    }

    private static EvaluationRunResult findResult(List<EvaluationRunResult> executions,
                                                  ExecutionPolicy policy) {
        return executions.stream()
                .filter(item -> item.policy() == policy)
                .findFirst().orElse(null);
    }

    private static List<PolicySummary> summarize(List<EvaluationRunResult> results) {
        return results.stream().collect(Collectors.groupingBy(EvaluationRunResult::policy,
                        () -> new EnumMap<>(ExecutionPolicy.class), Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    var values = entry.getValue();
                    var scored = values.stream()
                            .filter(value -> value.score().status() == EvaluationSampleStatus.SCORED)
                            .toList();
                    var qualityScored = values.stream()
                            .filter(value -> value.score().qualityScored()).toList();
                    int passed = (int) scored.stream().filter(value -> value.score().passed()).count();
                    int errors = values.size() - scored.size();
                    int judgeUnscored = (int) values.stream()
                            .filter(value -> value.judge().status() == EvaluationJudgeStatus.UNSCORED)
                            .count();
                    var quality = (ToDoubleFunction<EvaluationRunResult>)
                            value -> value.score().quality();
                    var latency = (ToDoubleFunction<EvaluationRunResult>)
                            value -> value.execution().latencyMs();
                    return new PolicySummary(entry.getKey(), values.size(), scored.size(), errors,
                            passed, scored.isEmpty() ? 0 : passed / (double) scored.size(),
                            qualityScored.size(), judgeUnscored,
                            average(qualityScored, quality),
                            average(scored, value -> value.score().groundedness()),
                            average(values, value -> value.execution().totalTokens()),
                            average(values, latency),
                            average(values, value -> value.execution().coordinationArtifacts()),
                            standardDeviation(qualityScored, quality),
                            standardDeviation(values, latency));
                }).sorted(java.util.Comparator.comparing(item -> item.policy().ordinal())).toList();
    }

    private static double average(List<EvaluationRunResult> values,
                                  ToDoubleFunction<EvaluationRunResult> value) {
        return values.stream().mapToDouble(value).average().orElse(0);
    }

    private static double standardDeviation(List<EvaluationRunResult> values,
                                            ToDoubleFunction<EvaluationRunResult> value) {
        if (values.isEmpty()) {
            return 0;
        }
        double average = average(values, value);
        double variance = values.stream().mapToDouble(item -> {
            double delta = value.applyAsDouble(item) - average;
            return delta * delta;
        }).average().orElse(0);
        return Math.sqrt(variance);
    }

    private static Map<ExecutionMode, Integer> modeDistribution(
            List<EvaluationRunResult> results) {
        var distribution = new EnumMap<ExecutionMode, Integer>(ExecutionMode.class);
        for (var result : results) {
            distribution.merge(result.selectedMode(), 1, Integer::sum);
        }
        return distribution;
    }

    private static String rubricVersion(EvaluationCase testCase) {
        String fileName = java.nio.file.Path.of(testCase.rubric()).getFileName().toString();
        return fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length())
                : fileName;
    }

    private static EvaluationCase customCase(CustomEvaluationRequest request) {
        return new EvaluationCase(
                "custom-" + UUID.randomUUID().toString().substring(0, 8),
                "CUSTOM", false,
                new TaskProperties(0.5, 1, 0, 0, 0.4, 0.2, 0, 30_000),
                new EvaluationCase.CampaignInput(
                        "自定义评测", request.task(), request.audience(),
                        List.of(request.channel()), request.constraints()),
                "fixtures/knowledge/brand-a-v1.json",
                "fixtures/search/campaign-a.json",
                List.of("approval_required"), List.of(), Set.of(),
                "WAITING_APPROVAL", "rubrics/content-v1.json", List.of(),
                Set.copyOf(request.policies()));
    }

    private record CaseRepetitionKey(String caseId, int repetition) {}

    private record ExecutionKey(String caseId, int repetition, ExecutionPolicy policy) {}

    private record RecordedExecution(
            int repetition,
            EvaluationCase testCase,
            EvaluationExecution execution) {

        private CaseRepetitionKey caseRepetitionKey() {
            return new CaseRepetitionKey(execution.caseId(), repetition);
        }

        private ExecutionKey key() {
            return new ExecutionKey(execution.caseId(), repetition, execution.policy());
        }
    }
}
