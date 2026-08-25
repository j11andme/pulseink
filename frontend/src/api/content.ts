import { bearerHeaders, requestJson } from "./http";

export interface ReviewIssueResponse {
  type: string;
  affectedTaskIds: string[];
  message: string;
}

export interface ReviewReportResponse {
  id: number;
  sourceArtifactId: string;
  sourceArtifactVersion: number;
  sourceArtifactStatus: string;
  passed: boolean;
  repairRound: number;
  issues: ReviewIssueResponse[];
  createdAt: string;
}

export interface ContentVersionResponse {
  id: number;
  versionNo: number;
  content: Record<string, unknown>;
  sourceRefs: string[];
  origin: "AGENT" | "HUMAN";
  sourceArtifactId: string | null;
  sourceArtifactVersion: number | null;
  sourceArtifactStatus: string | null;
  createdBy: number | null;
  createdAt: string;
}

export interface ApprovalResponse {
  id: number;
  contentVersionId: number;
  actorUserId: number;
  comment: string | null;
  createdAt: string;
}

export interface ContentItemResponse {
  id: number;
  runId: number;
  taskId: string;
  currentVersionNo: number;
  itemVersion: number;
  createdAt: string;
  updatedAt: string;
  versions: ContentVersionResponse[];
  approvals: ApprovalResponse[];
}

export interface RunContentsResponse {
  contents: ContentItemResponse[];
  reviews: ReviewReportResponse[];
}

export function getRunContents(
  accessToken: string,
  runId: number
): Promise<RunContentsResponse> {
  return requestJson<RunContentsResponse>(`/api/runs/${runId}/contents`, {
    headers: bearerHeaders(accessToken)
  });
}

export function createContentVersion(
  accessToken: string,
  contentId: number,
  request: {
    expectedCurrentVersionNo: number;
    expectedItemVersion: number;
    content: Record<string, unknown>;
    sourceRefs: string[];
  }
): Promise<ContentVersionResponse> {
  return requestJson<ContentVersionResponse>(
    `/api/contents/${contentId}/versions`,
    {
      method: "POST",
      headers: bearerHeaders(accessToken),
      body: JSON.stringify(request)
    }
  );
}

export function approveContentVersion(
  accessToken: string,
  contentId: number,
  request: {
    contentVersionId: number;
    expectedCurrentVersionNo: number;
    expectedItemVersion: number;
    comment?: string;
  }
): Promise<ApprovalResponse> {
  return requestJson<ApprovalResponse>(`/api/contents/${contentId}/approve`, {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify(request)
  });
}
