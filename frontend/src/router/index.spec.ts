import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory } from "vue-router";
import { beforeEach, describe, expect, it } from "vitest";
import { useAuthStore } from "../stores/auth";
import { createAppRouter } from "./index";

describe("authentication route guard", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("redirects an anonymous visitor to login", async () => {
    const router = createAppRouter(createMemoryHistory());

    await router.push("/campaigns");
    await router.isReady();

    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("allows an authenticated visitor to enter the campaigns list", async () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
    const router = createAppRouter(createMemoryHistory());

    await router.push("/campaigns");
    await router.isReady();

    expect(router.currentRoute.value.path).toBe("/campaigns");
  });

  it("defaults root to the overview route", async () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
    const router = createAppRouter(createMemoryHistory());

    await router.push("/");
    await router.isReady();

    expect(router.currentRoute.value.path).toBe("/overview");
  });

  it("guards every new protected product page", async () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
    const router = createAppRouter(createMemoryHistory());

    for (const path of ["/overview", "/knowledge", "/integrations", "/evaluations", "/playground"]) {
      await router.push(path);
      expect(router.currentRoute.value.path).toBe(path);
    }
  });

  it("exposes the protected Evaluation Lab route", () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
    const router = createAppRouter(createMemoryHistory());
    const paths = router.getRoutes().map((route) => route.path);

    expect(paths).toContain("/evaluations");
  });

  it("loads authenticated product pages lazily while keeping login eager", () => {
    const router = createAppRouter(createMemoryHistory());
    const login = router.getRoutes().find((route) => route.path === "/login");
    const productRoutes = router.getRoutes().filter((route) =>
      [
        "/overview",
        "/campaigns",
        "/campaigns/:campaignId",
        "/knowledge",
        "/integrations",
        "/playground",
        "/evaluations"
      ].includes(route.path)
    );

    expect(typeof login?.components?.default).not.toBe("function");
    expect(productRoutes).toHaveLength(7);
    for (const route of productRoutes) {
      expect(typeof route.components?.default).toBe("function");
    }
  });

  it("preserves the redirect query for a protected campaign studio page", async () => {
    const router = createAppRouter(createMemoryHistory());

    await router.push("/campaigns/42?tab=run&runId=108");
    await router.isReady();

    expect(router.currentRoute.value.path).toBe("/login");
    expect(router.currentRoute.value.query.redirect).toBe("/campaigns/42?tab=run&runId=108");
  });
});
