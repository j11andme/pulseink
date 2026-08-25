import { bearerHeaders, requestJson } from "./http";

export type InsightStatus = "PENDING" | "APPROVED" | "REJECTED" | string;
export type InsightIndexStatus =
  | "NOT_INDEXED"
  | "INDEXING"
  | "INDEXED"
  | "FAILED"
  | string;

export interface EvidenceRefResponse {
  contentVersionId: number;
  publicationId: number;
  metricFrom: string | null;
  metricTo: string | null;
}

export interface InsightResponse {
  id: number;
  campaignId: number;
  runId: number;
  category: string;
  title: string;
  insightText: string;
  scopeType: string;
  scopeValue: string;
  applicableChannels: string[];
  evidenceRefs: EvidenceRefResponse[];
  confidence: number;
  limitations: string[];
  status: InsightStatus;
  indexStatus: InsightIndexStatus;
  createdBy: number;
  reviewedBy: number | null;
  reviewComment: string | null;
  createdAt: string;
  reviewedAt: string | null;
  indexedAt: string | null;
}

export interface ApprovedInsightHit {
  insightId: number;
  sourceCampaignId: number;
  title: string;
  insightText: string;
  category: string;
  scopeType: string;
  scopeValue: string;
  applicableChannels: string[];
  confidence: number;
  approvedAt: string;
}

export function generateInsightCandidate(
  accessToken: string,
  runId: number
): Promise<InsightResponse> {
  return requestJson<InsightResponse>(`/api/runs/${runId}/insight-candidates`, {
    method: "POST",
    headers: bearerHeaders(accessToken)
  });
}

export function listInsightsByCampaign(
  accessToken: string,
  campaignId: number
): Promise<InsightResponse[]> {
  return requestJson<InsightResponse[]>(`/api/campaigns/${campaignId}/insights`, {
    headers: bearerHeaders(accessToken)
  });
}

export function decideInsight(
  accessToken: string,
  insightId: number,
  decision: "APPROVE" | "REJECT",
  comment?: string
): Promise<InsightResponse> {
  return requestJson<InsightResponse>(`/api/insights/${insightId}/decision`, {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify({ decision, comment })
  });
}

export function searchApprovedInsights(
  accessToken: string,
  query: string,
  channel?: string,
  topK = 0
): Promise<ApprovedInsightHit[]> {
  const params = new URLSearchParams({ query, topK: String(topK) });
  if (channel) {
    params.set("channel", channel);
  }
  return requestJson<ApprovedInsightHit[]>(
    `/api/insights/search?${params.toString()}`,
    { headers: bearerHeaders(accessToken) }
  );
}
