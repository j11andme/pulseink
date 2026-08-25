import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../stores/auth";
import EvaluationLabView from "./EvaluationLabView.vue";

const getEvaluationCases = vi.fn();
const getLatestEvaluationReport = vi.fn();
const runEvaluation = vi.fn();
const runCustomEvaluation = vi.fn();

vi.mock("../api/evaluation", () => ({
  getEvaluationCases: (...args: unknown[]) => getEvaluationCases(...args),
  getLatestEvaluationReport: (...args: unknown[]) => getLatestEvaluationReport(...args),
  runEvaluation: (...args: unknown[]) => runEvaluation(...args),
  runCustomEvaluation: (...args: unknown[]) => runCustomEvaluation(...args)
}));

const report = {
  reportId: "eval-1",
  generatedAt: "2026-08-20T09:00:00Z",
  suite: "SMOKE",
  repetitions: 1,
  datasetVersion: "pulseink-eval-v1",
  scorerVersion: "scorer-v1",
  claimsStatisticalSignificance: false,
  runtime: { provider: "ark", model: "ep-real", simulated: false },
  scoredSamples: 23,
  errorSamples: 1,
  judgeUnscoredSamples: 1,
  invalidSampleRate: 1 / 24,
  selectedModeDistribution: { DIRECT: 2, REACT: 6, ORCHESTRATED: 16 },
  summaries: [
    { policy: "DIRECT", executions: 6, scoredExecutions: 6, errors: 0,
      passed: 4, passRate: 0.667, qualitySamples: 0, judgeUnscored: 0,
      averageQuality: 0, averageGroundedness: 0.4, averageTokens: 30,
      averageLatencyMs: 10, averageCoordinationArtifacts: 0,
      qualityStdDev: 0, latencyStdDev: 0 },
    { policy: "ADAPTIVE", executions: 6, scoredExecutions: 5, errors: 1,
      passed: 5, passRate: 1, qualitySamples: 4, judgeUnscored: 1,
      averageQuality: 0, averageGroundedness: 0.7, averageTokens: 70,
      averageLatencyMs: 25, averageCoordinationArtifacts: 2,
      qualityStdDev: 0, latencyStdDev: 0 }
  ],
  executions: [
    {
      repetition: 1,
      execution: { caseId: "smoke-03-multichannel-orchestrated", policy: "ADAPTIVE",
        selectedMode: "ORCHESTRATED", finalState: "WAITING_APPROVAL",
        terminalReason: "SUCCEEDED", modelCalls: 3, toolCalls: 0,
        totalTokens: 90, latencyMs: 30, repairCount: 0, coordinationArtifacts: 3,
        toolTrace: [], trace: [{ sequence: 1, timestamp: "2026-08-20T09:00:01Z",
          eventType: "RUNTIME", actor: "runtime", subject: "INVALID_MODEL_OUTPUT",
          outcome: "FAILED", summary: "INVALID_MODEL_OUTPUT", evidence: [] }] },
      score: { status: "SCORED", passed: false, hardRulesPassed: false,
        qualityScored: false, quality: 0,
        groundedness: 0, recallAtK: 1, precisionAtK: 0.667, mrr: 1, ndcg: 1,
        trajectory: 1, violations: ["citation_required"],
        failure: { stage: "EVIDENCE", code: "citation_required",
          summary: "Observable evaluation rule failed: citation_required", evidence: [] } },
      judge: { executed: true, parseFailure: true, orders: ["AB", "BA"],
        judgeModel: "pulseink-fake", promptVersion: "judge-v2-explainable",
        rubricVersion: "content-v1", failureCode: "JUDGE_PARSE_FAILURE",
        status: "UNSCORED", explanation: "Judge returned invalid structured output" }
    }
  ],
  comparisons: [
    { caseId: "smoke-03-multichannel-orchestrated", repetition: 1,
      comparable: true, qualityDelta: 0, coordinationOverhead: 2.4,
      preferredPolicy: "REACT", reason: "" }
  ]
};

function mountView(role: "VIEWER" | "EDITOR" = "EDITOR") {
  setActivePinia(createPinia());
  useAuthStore().acceptSession({
    accessToken: "signed.jwt",
    expiresIn: 1800,
    user: { id: 1, username: "demo", role }
  });
  return mount(EvaluationLabView, {
    global: {
      stubs: {
        RouterLink: { props: ["to"], template: '<a :href="String(to)"><slot /></a>' }
      }
    }
  });
}

describe("EvaluationLabView", () => {
  beforeEach(() => {
    getEvaluationCases.mockReset().mockResolvedValue({
      smokeCount: 6,
      cases: Array.from({ length: 18 }, (_, index) => ({
        caseId: `case-${index + 1}`, category: "NORMAL", smoke: index < 6,
        goal: "goal", expectedRules: ["approval_required"],
        expectedFinalState: "WAITING_APPROVAL"
      }))
    });
    getLatestEvaluationReport.mockReset().mockResolvedValue(undefined);
    runEvaluation.mockReset().mockResolvedValue(report);
    runCustomEvaluation.mockReset().mockResolvedValue({
      ...report,
      suite: "CUSTOM",
      datasetVersion: "user-custom-v1",
      customCase: {
        task: "撰写 Java 秋招内容",
        expectedResult: "包含岗位职责与投递方式",
        audience: "Java 应届生",
        channel: "SOCIAL",
        constraints: ["语气专业"]
      }
    });
  });

  it("loads the 18-case catalog and renders a real four-policy report", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="evaluation-case-count"]').text()).toContain("18");
    expect(wrapper.get('[data-testid="evaluation-smoke-count"]').text()).toContain("6");

    await wrapper.get('[data-testid="run-evaluation"]').trigger("click");
    await flushPromises();

    expect(runEvaluation).toHaveBeenCalledWith("signed.jwt", {
      suite: "SMOKE",
      policies: ["DIRECT", "REACT", "ORCHESTRATED", "ADAPTIVE"],
      judgeEnabled: false
    });
    expect(wrapper.text()).toContain("ADAPTIVE");
    expect(wrapper.text()).toContain("ORCHESTRATED");
    expect(wrapper.text()).toContain("citation_required");
    expect(wrapper.text()).toContain("JUDGE_PARSE_FAILURE");
    expect(wrapper.text()).toContain("2.40×");
    expect(wrapper.text()).toContain("不声明统计显著性");
  });

  it("keeps Viewer read-only", async () => {
    const wrapper = mountView("VIEWER");
    await flushPromises();

    expect(wrapper.find('[data-testid="run-evaluation"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("只读");
  });

  it("runs a custom case without changing the fixed suite request", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get("#evaluation-suite").setValue("CUSTOM");
    await wrapper.get('[data-testid="custom-task"]').setValue("撰写 Java 秋招内容");
    await wrapper.get('[data-testid="custom-expected-result"]')
      .setValue("包含岗位职责与投递方式");
    await wrapper.get('[data-testid="custom-audience"]').setValue("Java 应届生");
    await wrapper.get('[data-testid="custom-channel"]').setValue("SOCIAL");
    await wrapper.get('[data-testid="custom-constraints"]').setValue("语气专业");
    expect((wrapper.get('[data-testid="custom-policy-DIRECT"]')
      .element as HTMLInputElement).checked).toBe(true);
    expect((wrapper.get('[data-testid="custom-policy-REACT"]')
      .element as HTMLInputElement).checked).toBe(false);
    await wrapper.get('[data-testid="custom-policy-REACT"]').setValue(true);
    await wrapper.get('[data-testid="custom-policy-ADAPTIVE"]').setValue(true);

    await wrapper.get('[data-testid="run-evaluation"]').trigger("click");
    await flushPromises();

    expect(runCustomEvaluation).toHaveBeenCalledWith("signed.jwt", {
      task: "撰写 Java 秋招内容",
      expectedResult: "包含岗位职责与投递方式",
      audience: "Java 应届生",
      channel: "SOCIAL",
      constraints: ["语气专业"],
      policies: ["DIRECT", "REACT", "ADAPTIVE"]
    });
    expect(runEvaluation).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("CUSTOM");
    expect(wrapper.text()).toContain("包含岗位职责与投递方式");
  });
});
