export interface ApiErrorBody {
  code?: string;
  message?: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function bearerHeaders(accessToken: string): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` };
}

type UnauthorizedHandler = () => void | Promise<void>;

let unauthorizedHandler: UnauthorizedHandler | undefined;

/**
 * Registers the single 401 session-recovery callback (wired in main.ts). The API module itself
 * never imports Pinia or the Router.
 */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler ?? undefined;
}

/**
 * JSON-oriented request helper. FormData bodies never receive a manual Content-Type so the
 * browser can add the multipart boundary; 204 responses resolve without body parsing.
 */
export async function requestJson<T>(
  input: RequestInfo | URL,
  init?: RequestInit
): Promise<T> {
  const headers = new Headers(init?.headers);
  const authenticatedRequest = headers.has("Authorization");
  const formDataBody =
    typeof FormData !== "undefined" && init?.body instanceof FormData;

  if (!headers.has("Accept")) {
    headers.set("Accept", "application/json");
  }
  if (!formDataBody && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(input, {
    ...init,
    headers
  });

  if (!response.ok) {
    const body = await readErrorBody(response);
    if (response.status === 401 && authenticatedRequest && unauthorizedHandler) {
      await unauthorizedHandler();
    }
    throw new ApiError(
      response.status,
      body.code ?? "REQUEST_FAILED",
      body.message ?? "请求失败，请稍后重试"
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

async function readErrorBody(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return {};
  }
}
