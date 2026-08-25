import { createPinia, setActivePinia } from "pinia";
import { mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../../stores/auth";
import AppShell from "./AppShell.vue";

const push = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({ push })
}));

type ShellRoute = "overview" | "campaigns" | "knowledge" | "integrations" | "playground" | "evaluation";

function mountShell(activeRoute: ShellRoute) {
  return mount(AppShell, {
    props: { activeRoute },
    global: {
      stubs: {
        RouterLink: {
          props: ["to"],
          template: '<a :href="String(to)"><slot /></a>'
        }
      }
    }
  });
}

describe("AppShell", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("renders the full product navigation, user role and logout", () => {
    const wrapper = mountShell("overview");

    expect(wrapper.text()).toContain("Overview");
    expect(wrapper.text()).toContain("Campaigns");
    expect(wrapper.text()).toContain("Knowledge");
    expect(wrapper.text()).toContain("Integrations");
    expect(wrapper.text()).toContain("Model Playground");
    expect(wrapper.text()).toContain("Evaluation Lab");
    expect(wrapper.text()).toContain("demo");
    expect(wrapper.text()).toContain("EDITOR");
    expect(wrapper.get('[data-testid="logout"]')).toBeTruthy();
  });

  it("keeps the primary navigation accessible as a wrapped row", () => {
    const wrapper = mountShell("campaigns");

    const nav = wrapper.get('[data-testid="shell-nav"]');
    expect(nav.classes()).toContain("shell-nav");
    expect(nav.findAll(".shell-nav-link")).toHaveLength(6);
  });

  it("clears the session and navigates to login on logout", async () => {
    const wrapper = mountShell("campaigns");

    await wrapper.get('[data-testid="logout"]').trigger("click");

    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith("/login");
  });
});
