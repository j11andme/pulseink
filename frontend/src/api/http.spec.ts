import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  registerUnauthorizedHandler,
  requestJson
} from "./http";

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    headers: new Headers()
  } as Response;
}

describe("requestJson", () => {
  beforeEach(() => {
    registerUnauthorizedHandler(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("does not set a JSON Content-Type for FormData bodies", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { ok: true })
    );
    vi.stubGlobal("fetch", fetchMock);
    const form = new FormData();
    form.append("file", new Blob(["text"], { type: "text/markdown" }), "a.md");

    await requestJson("/api/knowledge/documents", {
      method: "POST",
      headers: { Authorization: "Bearer signed.jwt" },
      body: form
    });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.has("Content-Type")).toBe(false);
    expect(headers.get("Authorization")).toBe("Bearer signed.jwt");
  });

  it("resolves undefined for 204 without parsing JSON", async () => {
    const json = vi.fn();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      json
    } as unknown as Response);
    vi.stubGlobal("fetch", fetchMock);

    const result = await requestJson<void>("/api/knowledge/documents/1/retry", {
      method: "POST",
      headers: { Authorization: "Bearer signed.jwt" }
    });

    expect(result).toBeUndefined();
    expect(json).not.toHaveBeenCalled();
  });

  it("invokes the 401 handler only for authenticated requests", async () => {
    const handler = vi.fn().mockResolvedValue(undefined);
    registerUnauthorizedHandler(handler);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(
        jsonResponse(401, { code: "UNAUTHENTICATED", message: "authentication is required" })
      )
      .mockResolvedValueOnce(
        jsonResponse(401, { code: "INVALID_CREDENTIALS", message: "invalid username or password" })
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      requestJson("/api/campaigns", {
        headers: { Authorization: "Bearer signed.jwt" }
      })
    ).rejects.toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
    expect(handler).toHaveBeenCalledTimes(1);

    await expect(
      requestJson("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ username: "demo", password: "wrong" })
      })
    ).rejects.toMatchObject({ status: 401, code: "INVALID_CREDENTIALS" });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("keeps the session on 403 and preserves the backend code and message", async () => {
    const handler = vi.fn();
    registerUnauthorizedHandler(handler);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      jsonResponse(403, { code: "ACCESS_DENIED", message: "access is denied" })
    ));

    await expect(
      requestJson("/api/campaigns", {
        method: "POST",
        headers: { Authorization: "Bearer signed.jwt" },
        body: "{}"
      })
    ).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(403);
      expect((error as ApiError).code).toBe("ACCESS_DENIED");
      expect((error as ApiError).message).toBe("access is denied");
      return true;
    });
    expect(handler).not.toHaveBeenCalled();
  });
});
