import { bearerHeaders, requestJson } from "./http";

export type ExecutionPolicy = "DIRECT" | "REACT" | "ORCHESTRATED" | "ADAPTIVE";
export type ExecutionMode = "DIRECT" | "REACT" | "ORCHESTRATED";

export interface TaskProperties {
  decomposability: number;
  channelCount: number;
  sourceDiversity: number;
  parallelResearchBranches: number;
  sequentialDependency: number;
  factualRisk: number;
  toolBreadth: number;
  latencyBudgetMs: number;
}

export interface StartRunRequest {
  requestedPolicy: ExecutionPolicy;
  taskProperties: TaskProperties;
}

export interface RunResponse {
  runId: number;
  campaignId: number;
  requestedPolicy: ExecutionPolicy;
  selectedMode: ExecutionMode | null;
  selectorPolicyVersion: string | null;
  reasonCodes: string[];
  featureSnapshot: Record<string, unknown>;
  estimatedTokenBudget: number;
  state: string;
  failureReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface RunDecision {
  requestedPolicy: ExecutionPolicy;
  selectedMode: ExecutionMode | null;
  reasonCodes: string[];
  selectorPolicyVersion: string | null;
  featureSnapshot?: Record<string, unknown>;
  estimatedTokenBudget?: number;
}

export interface ArtifactResponse {
  artifactId: string;
  taskId: string;
  type: string;
  schemaVersion: string;
  artifactVersion: number;
  status: "VALID" | "INVALIDATED";
  content: Record<string, unknown>;
  sourceRefs: string[];
  createdAt: string;
}

export interface BudgetResponse {
  modelCallsUsed: number;
  toolCallsUsed: number;
  tokensUsed: number;
  reactRoundsUsed: number;
}

export interface CheckpointResponse {
  checkpointType: string;
  schemaVersion: number;
  lastCompletedRound: number;
  lastPersistedEventSequence: number;
  createdAt: string;
  budget: BudgetResponse;
  artifacts: ArtifactResponse[];
}

export interface RunEventResponse {
  sequence: number;
  eventType: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export interface RunTraceResponse {
  run: RunResponse;
  lastEventSequence: number;
  checkpoint: CheckpointResponse | null;
  events: RunEventResponse[];
}

export function startRun(
  accessToken: string,
  campaignId: number,
  request: StartRunRequest
): Promise<RunResponse> {
  return requestJson<RunResponse>(`/api/campaigns/${campaignId}/runs`, {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify(request)
  });
}

export function listRuns(
  accessToken: string,
  campaignId: number
): Promise<RunResponse[]> {
  return requestJson<RunResponse[]>(`/api/campaigns/${campaignId}/runs`, {
    headers: bearerHeaders(accessToken)
  });
}

export function getRunTrace(
  accessToken: string,
  runId: number,
  signal?: AbortSignal
): Promise<RunTraceResponse> {
  return requestJson<RunTraceResponse>(`/api/runs/${runId}/trace`, {
    headers: bearerHeaders(accessToken),
    signal
  });
}
