package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.client.evaluation.FileSystemEvaluationCaseCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationCaseCatalogTest {

    @Test
    void repositoryDatasetContainsEighteenCasesAndSixSmokeCases() {
        var catalog = new FileSystemEvaluationCaseCatalog(
                Path.of("..", "evals"), new ObjectMapper().findAndRegisterModules());

        assertThat(catalog.all()).hasSize(18);
        assertThat(catalog.smokeCases())
                .hasSize(6)
                .allMatch(EvaluationCase::smoke);
        assertThat(catalog.all()).extracting(EvaluationCase::caseId).doesNotHaveDuplicates();
    }

    @Test
    void everyCaseReferencesVersionedFrozenInputs() {
        var catalog = new FileSystemEvaluationCaseCatalog(
                Path.of("..", "evals"), new ObjectMapper().findAndRegisterModules());

        assertThat(catalog.all()).allSatisfy(testCase -> {
            assertThat(testCase.knowledgeSnapshot()).startsWith("fixtures/knowledge/");
            assertThat(testCase.searchFixtures()).startsWith("fixtures/search/");
            assertThat(testCase.rubric()).isEqualTo("rubrics/content-v1.json");
            assertThat(testCase.taskProperties().latencyBudgetMs()).isPositive();
        });
    }

    @Test
    void rejectsUnknownFieldsAndReferencesEscapingTheDatasetRoot(
            @TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("cases"));
        Path caseFile = root.resolve("cases/strict-case.json");
        Files.writeString(caseFile, validCase("fixtures/knowledge/brand.json")
                .replace("\"failureInjection\":[]",
                        "\"failureInjection\":[],\"unknown\":true"));
        var unknownFieldCatalog = new FileSystemEvaluationCaseCatalog(
                root, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(unknownFieldCatalog::all)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid evaluation case");

        Files.writeString(root.resolve("outside.json"), "{}");
        Files.writeString(caseFile, validCase("../outside.json"));
        Files.createDirectories(root.resolve("fixtures/search"));
        Files.createDirectories(root.resolve("rubrics"));
        Files.writeString(root.resolve("fixtures/search/search.json"), "{}");
        Files.writeString(root.resolve("rubrics/content-v1.json"), "{}");
        var escapingCatalog = new FileSystemEvaluationCaseCatalog(
                root, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(escapingCatalog::all)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid evaluation fixture reference");
    }

    private static String validCase(String knowledgeSnapshot) {
        return """
                {
                  "caseId":"strict-case","category":"NORMAL","smoke":true,
                  "taskProperties":{"decomposability":0.2,"channelCount":1,
                    "sourceDiversity":1,"parallelResearchBranches":0,
                    "sequentialDependency":0.3,"factualRisk":0.2,
                    "toolBreadth":0,"latencyBudgetMs":1000},
                  "campaignInput":{"name":"n","goal":"g","audience":"a",
                    "channels":["BLOG"],"constraints":[]},
                  "knowledgeSnapshot":"%s",
                  "searchFixtures":"fixtures/search/search.json",
                  "expectedRules":[],"relevantChunkIds":[],"allowedTools":[],
                  "expectedFinalState":"WAITING_APPROVAL",
                  "rubric":"rubrics/content-v1.json","failureInjection":[]
                }
                """.formatted(knowledgeSnapshot);
    }
}
