import { ApiError, bearerHeaders, requestJson } from "./http";

export type EvaluationPolicy = "DIRECT" | "REACT" | "ORCHESTRATED" | "ADAPTIVE";
export type ExecutionMode = "DIRECT" | "REACT" | "ORCHESTRATED";
export type EvaluationSuite = "SMOKE" | "FULL" | "STABILITY" | "CUSTOM";
export type EvaluationChannel = "BLOG" | "SOCIAL" | "SHORT_VIDEO";

export interface EvaluationCaseSummary {
  caseId: string;
  category: string;
  smoke: boolean;
  goal: string;
  expectedRules: string[];
  expectedFinalState: string;
  applicablePolicies: EvaluationPolicy[];
}

export interface EvaluationCaseList {
  cases: EvaluationCaseSummary[];
  smokeCount: number;
}

export interface EvaluationPolicySummary {
  policy: EvaluationPolicy;
  executions: number;
  scoredExecutions: number;
  errors: number;
  passed: number;
  passRate: number;
  qualitySamples: number;
  judgeUnscored: number;
  averageQuality: number;
  averageGroundedness: number;
  averageTokens: number;
  averageLatencyMs: number;
  averageCoordinationArtifacts: number;
  qualityStdDev: number;
  latencyStdDev: number;
}

export interface EvaluationRunResult {
  repetition: number;
  execution: {
    caseId: string;
    policy: EvaluationPolicy;
    selectedMode: ExecutionMode;
    finalState: string;
    terminalReason: string;
    modelCalls: number;
    toolCalls: number;
    totalTokens: number;
    latencyMs: number;
    repairCount: number;
    coordinationArtifacts: number;
    candidateText: string;
    toolTrace: Array<{
      sequence: number;
      qualifiedName: string;
      arguments: Record<string, string>;
      outcome: string;
      sourceRefs: string[];
    }>;
    trace: Array<{
      sequence: number;
      timestamp: string;
      eventType: string;
      actor: string;
      subject: string;
      outcome: string;
      summary: string;
      evidence: string[];
    }>;
  };
  score: {
    status: "SCORED" | "ERROR";
    passed: boolean;
    hardRulesPassed: boolean;
    qualityScored: boolean;
    quality: number;
    groundedness: number;
    recallAtK: number;
    precisionAtK: number;
    mrr: number;
    ndcg: number;
    trajectory: number;
    violations: string[];
    failure: {
      stage: string;
      code: string;
      summary: string;
      evidence: string[];
    };
  };
  judge: {
    executed: boolean;
    parseFailure: boolean;
    orders: string[];
    judgeModel: string;
    promptVersion: string;
    rubricVersion: string;
    failureCode: string;
    status: "NOT_RUN" | "SCORED" | "UNSCORED";
    explanation: string;
  };
}

export interface PolicyAblationComparison {
  caseId: string;
  repetition: number;
  comparable: boolean;
  qualityDelta: number;
  coordinationOverhead: number;
  preferredPolicy?: EvaluationPolicy;
  reason: string;
}

export interface EvaluationReport {
  reportId: string;
  generatedAt: string;
  suite: EvaluationSuite;
  repetitions: number;
  executions: EvaluationRunResult[];
  summaries: EvaluationPolicySummary[];
  comparisons: PolicyAblationComparison[];
  selectedModeDistribution: Partial<Record<ExecutionMode, number>>;
  scoredSamples: number;
  errorSamples: number;
  judgeUnscoredSamples: number;
  invalidSampleRate: number;
  claimsStatisticalSignificance: boolean;
  runtime: {
    provider: string;
    model: string;
    simulated: boolean;
  };
  replayedFromReportId: string;
  datasetVersion: string;
  scorerVersion: string;
  customCase?: {
    task: string;
    expectedResult: string;
    audience: string;
    channel: EvaluationChannel;
    constraints: string[];
  } | null;
}

export interface RunEvaluationPayload {
  suite: EvaluationSuite;
  policies: EvaluationPolicy[];
  judgeEnabled: boolean;
}

export interface RunCustomEvaluationPayload {
  task: string;
  expectedResult: string;
  audience: string;
  channel: EvaluationChannel;
  constraints: string[];
  policies: EvaluationPolicy[];
}

export function getEvaluationCases(accessToken: string): Promise<EvaluationCaseList> {
  return requestJson("/api/evaluations/cases", {
    headers: bearerHeaders(accessToken)
  });
}

export async function getLatestEvaluationReport(
  accessToken: string
): Promise<EvaluationReport | undefined> {
  try {
    return await requestJson("/api/evaluations/reports/latest", {
      headers: bearerHeaders(accessToken)
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return undefined;
    }
    throw error;
  }
}

export function runEvaluation(
  accessToken: string,
  payload: RunEvaluationPayload
): Promise<EvaluationReport> {
  return requestJson("/api/evaluations/runs", {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify(payload)
  });
}

export function runCustomEvaluation(
  accessToken: string,
  payload: RunCustomEvaluationPayload
): Promise<EvaluationReport> {
  return requestJson("/api/evaluations/runs/custom", {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify(payload)
  });
}
