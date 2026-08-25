import { bearerHeaders, requestJson } from "./http";

export type KnowledgeType = "PRODUCT" | "BRAND" | "CHANNEL_RULE" | "CASE_STUDY" | string;
export type EvidenceAuthority =
  | "OFFICIAL"
  | "INTERNAL"
  | "THIRD_PARTY"
  | string;
export type KnowledgeDocumentStatus =
  | "PENDING"
  | "PROCESSING"
  | "ACTIVE"
  | "FAILED"
  | string;

export interface KnowledgeDocumentItem {
  documentId: number;
  sourceId: string;
  originalFilename: string;
  declaredMimeType: string | null;
  detectedMimeType: string | null;
  sizeBytes: number;
  knowledgeType: KnowledgeType;
  authority: EvidenceAuthority;
  documentVersion: number;
  status: KnowledgeDocumentStatus;
  chunkCount: number;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocumentPage {
  total: number;
  items: KnowledgeDocumentItem[];
}

export interface KnowledgeUploadResponse {
  documentId: number;
  sourceId: string;
  jobId: string;
  status: string;
}

export interface KnowledgeEvidence {
  sourceId: string;
  title: string;
  heading: string | null;
  snippet: string;
  score: number;
  channels: string[];
  type: string;
  authority: string;
  updatedAt: string | null;
}

export interface SearchTestResponse {
  retrievalMode: string;
  degradedReasonCode: string | null;
  evidence: KnowledgeEvidence[];
}

export function listKnowledgeDocuments(
  accessToken: string,
  filters: {
    status?: KnowledgeDocumentStatus;
    type?: KnowledgeType;
    page?: number;
    size?: number;
  } = {}
): Promise<KnowledgeDocumentPage> {
  const params = new URLSearchParams();
  if (filters.status) {
    params.set("status", filters.status);
  }
  if (filters.type) {
    params.set("type", filters.type);
  }
  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? 20));
  return requestJson<KnowledgeDocumentPage>(
    `/api/knowledge/documents?${params.toString()}`,
    { headers: bearerHeaders(accessToken) }
  );
}

export function uploadKnowledgeDocument(
  accessToken: string,
  file: File,
  knowledgeType: string,
  authority: string
): Promise<KnowledgeUploadResponse> {
  const form = new FormData();
  form.append("file", file);
  form.append("knowledgeType", knowledgeType);
  form.append("authority", authority);
  return requestJson<KnowledgeUploadResponse>("/api/knowledge/documents", {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: form
  });
}

export function retryKnowledgeDocument(
  accessToken: string,
  documentId: number
): Promise<void> {
  return requestJson<void>(`/api/knowledge/documents/${documentId}/retry`, {
    method: "POST",
    headers: bearerHeaders(accessToken)
  });
}

export function searchKnowledge(
  accessToken: string,
  request: {
    query: string;
    types?: string[];
    authorities?: string[];
    updatedAfter?: string | null;
    topK?: number;
  }
): Promise<SearchTestResponse> {
  return requestJson<SearchTestResponse>("/api/knowledge/search-test", {
    method: "POST",
    headers: bearerHeaders(accessToken),
    body: JSON.stringify(request)
  });
}
