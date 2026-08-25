import { requestJson } from "./http";

export type CampaignChannel = "BLOG" | "SOCIAL" | "SHORT_VIDEO";

export interface CampaignResponse {
  id: number;
  name: string;
  objective: string;
  audience: string;
  channels: CampaignChannel[];
  constraints: string[];
  status: string;
  createdBy: number;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface CampaignPageResponse {
  items: CampaignResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateCampaignRequest {
  name: string;
  objective: string;
  audience: string;
  channels: CampaignChannel[];
  constraints: string[];
}

function authHeaders(accessToken: string): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function createCampaign(
  accessToken: string,
  request: CreateCampaignRequest
): Promise<CampaignResponse> {
  return requestJson<CampaignResponse>("/api/campaigns", {
    method: "POST",
    headers: authHeaders(accessToken),
    body: JSON.stringify(request)
  });
}

export async function listCampaigns(
  accessToken: string,
  page = 0,
  size = 20
): Promise<CampaignPageResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return requestJson<CampaignPageResponse>(
    `/api/campaigns?${params.toString()}`,
    { headers: authHeaders(accessToken) }
  );
}

export async function getCampaign(
  accessToken: string,
  campaignId: number
): Promise<CampaignResponse> {
  return requestJson<CampaignResponse>(`/api/campaigns/${campaignId}`, {
    headers: authHeaders(accessToken)
  });
}
