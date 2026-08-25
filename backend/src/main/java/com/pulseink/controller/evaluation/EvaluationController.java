package com.pulseink.controller.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.evaluation.CustomEvaluationRequest;
import com.pulseink.service.evaluation.EvaluationCase;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import com.pulseink.service.evaluation.EvaluationReport;
import com.pulseink.service.evaluation.EvaluationRequest;
import com.pulseink.service.evaluation.EvaluationSuite;
import com.pulseink.service.evaluation.RunEvaluationUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationCaseCatalog catalog;
    private final RunEvaluationUseCase useCase;

    public EvaluationController(EvaluationCaseCatalog catalog,
                                RunEvaluationUseCase useCase) {
        this.catalog = catalog;
        this.useCase = useCase;
    }

    @GetMapping("/cases")
    public CaseListResponse cases() {
        var cases = catalog.all().stream().map(CaseSummary::from).toList();
        return new CaseListResponse(cases, (int) cases.stream().filter(CaseSummary::smoke).count());
    }

    @PostMapping("/runs")
    public EvaluationReport run(@Valid @RequestBody RunRequest request) {
        return useCase.run(new EvaluationRequest(
                request.suite(), request.policies(), request.judgeEnabled()));
    }

    @PostMapping("/runs/custom")
    public EvaluationReport runCustom(@Valid @RequestBody CustomRunRequest request) {
        return useCase.runCustom(new CustomEvaluationRequest(
                request.task(), request.expectedResult(), request.audience(),
                request.channel(), request.constraints(), request.policies()));
    }

    @GetMapping("/reports/latest")
    public ResponseEntity<EvaluationReport> latest() {
        return useCase.latest().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record RunRequest(
            @NotNull EvaluationSuite suite,
            List<ExecutionPolicy> policies,
            boolean judgeEnabled) {}

    public record CustomRunRequest(
            @NotBlank @Size(max = 2_000) String task,
            @NotBlank @Size(max = 4_000) String expectedResult,
            @NotBlank @Size(max = 200) String audience,
            @NotNull CampaignChannel channel,
            @Size(max = 10) List<@NotBlank @Size(max = 300) String> constraints,
            @NotEmpty List<@NotNull ExecutionPolicy> policies) {}

    public record CaseListResponse(List<CaseSummary> cases, int smokeCount) {}

    public record CaseSummary(
            String caseId,
            String category,
            boolean smoke,
            String goal,
            List<String> expectedRules,
            String expectedFinalState,
            java.util.Set<ExecutionPolicy> applicablePolicies) {
        static CaseSummary from(EvaluationCase testCase) {
            return new CaseSummary(testCase.caseId(), testCase.category(), testCase.smoke(),
                    testCase.campaignInput().goal(), testCase.expectedRules(),
                    testCase.expectedFinalState(), testCase.applicablePolicies());
        }
    }
}
