package com.pulseink.service.evaluation;

@FunctionalInterface
public interface EvaluationJudge {
    JudgeScore compare(EvaluationCase testCase,
                       EvaluationExecution candidateA,
                       EvaluationExecution candidateB);

    default JudgeScore scoreAgainstReference(EvaluationCase testCase,
                                             EvaluationExecution candidate,
                                             String expectedResult) {
        var reference = new EvaluationExecution(
                testCase.caseId(), candidate.policy(), candidate.selectedMode(),
                testCase.expectedFinalState(),
                com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED,
                java.util.List.of(), java.util.List.of(), java.util.Set.of(),
                0, 0, 0, 0, 0, 0, 0, expectedResult);
        return compare(testCase, candidate, reference);
    }
}
