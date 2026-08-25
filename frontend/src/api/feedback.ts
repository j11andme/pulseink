import { bearerHeaders, requestJson } from "./http";

export interface RunMetricResponse {
  publicationId: number;
  metricDate: string;
  views: number;
  clicks: number;
  likes: number;
}

export function getRunMetrics(
  accessToken: string,
  runId: number
): Promise<RunMetricResponse[]> {
  return requestJson<RunMetricResponse[]>(`/api/runs/${runId}/metrics`, {
    headers: bearerHeaders(accessToken)
  });
}
