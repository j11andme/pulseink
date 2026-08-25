import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getIntegrations } from "../api/integration";
import { useAuthStore } from "../stores/auth";
import IntegrationsView from "./IntegrationsView.vue";

vi.mock("../api/integration", () => ({
  getIntegrations: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const getIntegrationsMock = vi.mocked(getIntegrations);

function mountIntegrations() {
  return mount(IntegrationsView, {
    global: {
      stubs: {
        AppShell: { template: '<div><slot /></div>' }
      }
    }
  });
}

describe("IntegrationsView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getIntegrationsMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
  });

  it("groups integrations by category and renders tool names with risk", async () => {
    getIntegrationsMock.mockResolvedValue({
      integrations: [
        {
          id: "model", displayName: "模型 Provider", category: "MODEL",
          status: "CONFIGURED", summary: "本地演示 Provider：fake",
          capabilities: ["统一模型端口"]
        },
        {
          id: "kafka-feedback", displayName: "Kafka Feedback", category: "MESSAGING",
          status: "DISABLED", summary: "消费者已禁用", capabilities: ["Inbox 去重"]
        },
        {
          id: "tool-registry", displayName: "Tool Registry / MCP", category: "TOOLS",
          status: "CONFIGURED", summary: "已发现 2 个工具",
          capabilities: ["风险分级"]
        }
      ],
      tools: [
        { qualifiedName: "builtin.publish_content", risk: "EXTERNAL_SIDE_EFFECT",
          description: "Publish through sandbox" },
        { qualifiedName: "mcp.docs.search", risk: "READ", description: "Read-only search" }
      ]
    });
    const wrapper = mountIntegrations();
    await flushPromises();

    expect(wrapper.text()).toContain("MODEL");
    expect(wrapper.text()).toContain("MESSAGING");
    expect(wrapper.text()).toContain("TOOLS");
    expect(wrapper.text()).toContain("Kafka Feedback");
    expect(wrapper.text()).toContain("builtin.publish_content");
    expect(wrapper.text()).toContain("EXTERNAL_SIDE_EFFECT");
    expect(wrapper.text()).toContain("mcp.docs.search");
    expect(wrapper.text()).toContain("READ");
  });

  it("never renders unexpected secret-like fields returned by the backend", async () => {
    getIntegrationsMock.mockResolvedValue({
      integrations: [
        {
          id: "model", displayName: "模型 Provider", category: "MODEL",
          status: "CONFIGURED", summary: "fake",
          capabilities: [],
          apiKey: "sk-should-not-render",
          baseUrl: "http://internal/api/v3",
          token: "secret-token"
        }
      ],
      tools: []
    } as never);
    const wrapper = mountIntegrations();
    await flushPromises();

    expect(wrapper.text()).not.toContain("sk-should-not-render");
    expect(wrapper.text()).not.toContain("http://internal");
    expect(wrapper.text()).not.toContain("secret-token");
  });

  it("shows loading, empty, error and retry states", async () => {
    getIntegrationsMock.mockResolvedValue({ integrations: [], tools: [] });
    const wrapper = mountIntegrations();
    expect(wrapper.get('[data-testid="integrations-loading"]').text()).toContain("加载");
    await flushPromises();
    expect(wrapper.get('[data-testid="integrations-empty"]').text()).toContain("暂无");

    getIntegrationsMock.mockRejectedValue(new Error("network down"));
    await wrapper.get('[data-testid="integrations-reload"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="integrations-error"]').text()).toContain("加载失败");
    expect(getIntegrationsMock).toHaveBeenCalledTimes(2);
  });
});
