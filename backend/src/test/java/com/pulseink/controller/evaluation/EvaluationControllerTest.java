package com.pulseink.controller.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.client.evaluation.FileSystemEvaluationCaseCatalog;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import com.pulseink.service.evaluation.EvaluationSuite;
import com.pulseink.service.evaluation.RunEvaluationUseCase;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvaluationControllerTest {

    @Test
    void exposesCaseMetadataWithoutPromptsOrProviderConfiguration() {
        var controller = new EvaluationController(catalog(), mock(RunEvaluationUseCase.class));

        var response = controller.cases();

        assertThat(response.cases()).hasSize(18);
        assertThat(response.smokeCount()).isEqualTo(6);
        assertThat(response.cases().getFirst().goal()).isNotBlank();
    }

    @Test
    void mapsRunRequestAndReturnsNotFoundWhenNoReportExists() {
        var useCase = mock(RunEvaluationUseCase.class);
        org.mockito.Mockito.when(useCase.latest()).thenReturn(Optional.empty());
        var controller = new EvaluationController(catalog(), useCase);

        controller.run(new EvaluationController.RunRequest(
                EvaluationSuite.SMOKE,
                List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.ADAPTIVE), false));

        verify(useCase).run(any());
        assertThat(controller.latest().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void mapsCustomRunWithoutAddingItToTheFixedCatalog() {
        var useCase = mock(RunEvaluationUseCase.class);
        var controller = new EvaluationController(catalog(), useCase);

        controller.runCustom(new EvaluationController.CustomRunRequest(
                "撰写招聘内容", "包含岗位职责与投递方式", "Java 应届生",
                CampaignChannel.SOCIAL, List.of("语气专业"),
                List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT)));

        verify(useCase).runCustom(any());
        assertThat(controller.cases().cases()).hasSize(18);
    }

    private static EvaluationCaseCatalog catalog() {
        return new FileSystemEvaluationCaseCatalog(
                Path.of("..", "evals"), new ObjectMapper().findAndRegisterModules());
    }
}
