import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import LoginView from "./LoginView.vue";

const push = vi.fn();
const currentRoute = { value: { query: {} } };

vi.mock("vue-router", () => ({
  useRouter: () => ({ push, currentRoute })
}));

describe("LoginView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("logs in with the entered credentials and opens the playground", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          accessToken: "signed.jwt",
          expiresIn: 1800,
          user: { id: 1, username: "demo", role: "EDITOR" }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );
    const wrapper = mount(LoginView);

    await wrapper.get('[data-testid="username"]').setValue("demo");
    await wrapper.get('[data-testid="password"]').setValue("pulseink-demo");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/login",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ username: "demo", password: "pulseink-demo" })
      })
    );
    expect(push).toHaveBeenCalledWith("/campaigns");
  });

  it("shows the server message without navigating when credentials are invalid", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "INVALID_CREDENTIALS",
          message: "invalid username or password"
        }),
        { status: 401, headers: { "Content-Type": "application/json" } }
      )
    );
    const wrapper = mount(LoginView);

    await wrapper.get('[data-testid="username"]').setValue("demo");
    await wrapper.get('[data-testid="password"]').setValue("wrong");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain(
      "invalid username or password"
    );
    expect(push).not.toHaveBeenCalled();
  });
});
