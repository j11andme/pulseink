import { bearerHeaders, requestJson } from "./http";

export type PublicationStatus =
  | "PENDING"
  | "SENDING"
  | "PUBLISHED"
  | "RETRY_WAIT"
  | "FAILED"
  | string;

export interface PublicationResponse {
  id: number;
  runId: number;
  contentVersionId: number;
  channel: string;
  idempotencyKey: string;
  status: PublicationStatus;
  attemptCount: number;
  externalPostId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
}

export function publishContent(
  accessToken: string,
  contentId: number,
  contentVersionId: number,
  channel: string
): Promise<PublicationResponse> {
  return requestJson<PublicationResponse>(
    `/api/contents/${contentId}/publications`,
    {
      method: "POST",
      headers: bearerHeaders(accessToken),
      body: JSON.stringify({ contentVersionId, channel })
    }
  );
}

export function listPublicationsByRun(
  accessToken: string,
  runId: number
): Promise<PublicationResponse[]> {
  return requestJson<PublicationResponse[]>(`/api/runs/${runId}/publications`, {
    headers: bearerHeaders(accessToken)
  });
}

export function getPublication(
  accessToken: string,
  publicationId: number
): Promise<PublicationResponse> {
  return requestJson<PublicationResponse>(
    `/api/publications/${publicationId}`,
    { headers: bearerHeaders(accessToken) }
  );
}

export function returnPublicationToEditing(
  accessToken: string,
  publicationId: number
): Promise<void> {
  return requestJson<void>(
    `/api/publications/${publicationId}/return-to-editing`,
    {
      method: "POST",
      headers: bearerHeaders(accessToken)
    }
  );
}
