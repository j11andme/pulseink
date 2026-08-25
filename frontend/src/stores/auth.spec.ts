import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "./auth";

describe("auth store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-03T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("converts the relative token lifetime into an absolute expiry time", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          accessToken: "signed.jwt",
          expiresIn: 1800,
          user: { id: 1, username: "demo", role: "EDITOR" }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );
    const auth = useAuthStore();

    await auth.login("demo", "pulseink-demo");

    expect(auth.expiresAt).toBe(Date.parse("2026-08-03T12:30:00Z"));
  });

  it("removes the complete in-memory session on logout", () => {
    const auth = useAuthStore();
    auth.acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });

    auth.logout();

    expect(auth.accessToken).toBeUndefined();
    expect(auth.expiresAt).toBeUndefined();
    expect(auth.user).toBeUndefined();
    expect(auth.isAuthenticated).toBe(false);
  });
});
