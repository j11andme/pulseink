package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.client.model.FakeModelAdapter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmJudgeScorerTest {

    @Test
    void judgesBothAnonymousOrdersAndNormalizesScoresBackToOriginalCandidates() {
        var model = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"schemaVersion":1,"candidateAScore":0.8,"candidateBScore":0.6,
                         "candidateAReason":"better goal fit","candidateBReason":"less actionable"}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"schemaVersion":1,"candidateAScore":0.6,"candidateBScore":0.8,
                         "candidateAReason":"less actionable","candidateBReason":"better goal fit"}
                        """)));
        var scorer = new LlmJudgeScorer(model, new ObjectMapper(), Duration.ofSeconds(2));

        var result = scorer.scoreBothOrders("first candidate", "second candidate", "content-v1");

        assertThat(result.orders()).containsExactly("AB", "BA");
        assertThat(result.candidateAScore()).isEqualTo(0.8);
        assertThat(result.candidateBScore()).isEqualTo(0.6);
        assertThat(result.parseFailure()).isFalse();
        assertThat(result.promptVersion()).isEqualTo("judge-v2-explainable");
        assertThat(result.explanation()).contains("better goal fit");
    }

    @Test
    void reportsStrictParseFailureInsteadOfInventingAScore() {
        var model = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not-json"),
                FakeModelAdapter.Scene.of("not-json")));
        var scorer = new LlmJudgeScorer(model, new ObjectMapper(), Duration.ofSeconds(2));

        var result = scorer.scoreBothOrders("first", "second", "content-v1");

        assertThat(result.parseFailure()).isTrue();
        assertThat(result.failureCode()).isEqualTo("JUDGE_PARSE_FAILURE");
    }
}
