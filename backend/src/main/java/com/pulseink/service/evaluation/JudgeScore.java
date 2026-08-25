package com.pulseink.service.evaluation;

import java.util.List;

public record JudgeScore(
        boolean executed,
        boolean parseFailure,
        double candidateAScore,
        double candidateBScore,
        List<String> orders,
        String judgeModel,
        String promptVersion,
        String rubricVersion,
        String failureCode,
        EvaluationJudgeStatus status,
        String explanation) {

    public JudgeScore {
        orders = orders == null ? List.of() : List.copyOf(orders);
        judgeModel = judgeModel == null ? "" : judgeModel;
        promptVersion = promptVersion == null ? "" : promptVersion;
        rubricVersion = rubricVersion == null ? "" : rubricVersion;
        failureCode = failureCode == null ? "" : failureCode;
        status = status == null ? EvaluationJudgeStatus.NOT_RUN : status;
        explanation = explanation == null ? "" : explanation;
        if (candidateAScore < 0 || candidateAScore > 1
                || candidateBScore < 0 || candidateBScore > 1) {
            throw new IllegalArgumentException("judge scores must be between 0 and 1");
        }
    }

    public static JudgeScore success(double a, double b, String model,
                                     String promptVersion, String rubricVersion) {
        return success(a, b, model, promptVersion, rubricVersion, "");
    }

    public static JudgeScore success(double a, double b, String model,
                                     String promptVersion, String rubricVersion,
                                     String explanation) {
        return new JudgeScore(true, false, a, b, List.of("AB", "BA"), model,
                promptVersion, rubricVersion, "", EvaluationJudgeStatus.SCORED,
                explanation);
    }

    public static JudgeScore notRun(String rubricVersion) {
        return new JudgeScore(false, false, 0, 0, List.of(), "", "", rubricVersion, "",
                EvaluationJudgeStatus.NOT_RUN, "");
    }

    public static JudgeScore parseFailure(String model, String promptVersion,
                                          String rubricVersion) {
        return unscored(model, promptVersion, rubricVersion,
                "JUDGE_PARSE_FAILURE", "Judge returned invalid structured output");
    }

    public static JudgeScore unscored(String model, String promptVersion,
                                      String rubricVersion, String code,
                                      String explanation) {
        return new JudgeScore(true, true, 0, 0, List.of("AB", "BA"), model,
                promptVersion, rubricVersion, code, EvaluationJudgeStatus.UNSCORED,
                explanation);
    }

    public JudgeScore swappedCandidates() {
        return new JudgeScore(executed, parseFailure, candidateBScore, candidateAScore,
                orders, judgeModel, promptVersion, rubricVersion, failureCode,
                status, explanation);
    }
}
