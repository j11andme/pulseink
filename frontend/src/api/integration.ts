import { bearerHeaders, requestJson } from "./http";

export interface IntegrationItem {
  id: string;
  displayName: string;
  category: string;
  status: "CONFIGURED" | "DISABLED";
  summary: string;
  capabilities: string[];
}

export interface IntegrationTool {
  qualifiedName: string;
  risk: string;
  description: string;
}

export interface IntegrationStatusResponse {
  integrations: IntegrationItem[];
  tools: IntegrationTool[];
}

export function getIntegrations(
  accessToken: string
): Promise<IntegrationStatusResponse> {
  return requestJson<IntegrationStatusResponse>("/api/integrations", {
    headers: bearerHeaders(accessToken)
  });
}
