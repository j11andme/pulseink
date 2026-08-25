import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/http";
import { getCampaign, type CampaignResponse } from "../api/campaign";
import { useAuthStore } from "../stores/auth";
import CampaignStudioView from "./CampaignStudioView.vue";

vi.mock("../api/campaign", () => ({
  getCampaign: vi.fn()
}));

const replace = vi.fn();
const push = vi.fn();
let currentParams: Record<string, string> = { campaignId: "7" };
let currentQuery: Record<string, string> = { tab: "brief" };

vi.mock("vue-router", () => ({
  useRoute: () => ({ params: currentParams, query: currentQuery }),
  useRouter: () => ({ push, replace })
}));

const getCampaignMock = vi.mocked(getCampaign);

const campaign = {
  id: 7,
  name: "PulseInk 秋招发布",
  objective: "objective",
  audience: "audience",
  channels: ["BLOG", "SOCIAL"],
  constraints: [],
  status: "DRAFT",
  createdBy: 1,
  version: 0,
  createdAt: "2026-08-04T12:00:00Z",
  updatedAt: "2026-08-04T12:00:00Z"
} satisfies CampaignResponse;

const RunWorkspaceStub = {
  name: "RunWorkspace",
  props: ["selectedRunId"],
  emits: ["update:runId"],
  template: '<div data-testid="run-workspace">{{ selectedRunId }}</div>'
};

const childStubs = {
  BriefPanel: { name: "BriefPanel", template: '<div data-testid="brief-panel"><slot /></div>' },
  RunSetupPanel: { name: "RunSetupPanel", template: '<div data-testid="run-setup-panel"><slot /></div>' },
  RunWorkspace: RunWorkspaceStub,
  ContentWorkflowPanel: { name: "ContentWorkflowPanel", props: ["runId"], template: '<div data-testid="content-panel"><slot /></div>' },
  PublishPanel: { name: "PublishPanel", props: ["runId"], template: '<div data-testid="publish-panel"><slot /></div>' },
  MetricsPanel: { name: "MetricsPanel", props: ["runId"], template: '<div data-testid="metrics-panel"><slot /></div>' },
  InsightPanel: { name: "InsightPanel", props: ["runId"], template: '<div data-testid="insight-panel"><slot /></div>' }
};

function mountStudio() {
  return mount(CampaignStudioView, {
    global: {
      stubs: {
        AppShell: { template: '<div><slot /></div>' },
        RouterLink: { props: ["to"], template: '<a :href="String(to)"><slot /></a>' },
        ...childStubs
      }
    }
  });
}

describe("CampaignStudioView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getCampaignMock.mockReset();
    replace.mockReset();
    push.mockReset();
    currentParams = { campaignId: "7" };
    currentQuery = { tab: "brief" };
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("loads campaign detail with six tabs and Beijing time", async () => {
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    expect(wrapper.text()).toContain("PulseInk 秋招发布");
    expect(wrapper.text()).toContain("2026-08-04 20:00:00");
    for (const tab of ["Brief", "Run", "Content", "Publish", "Metrics", "Insights"]) {
      expect(wrapper.get(`[data-testid="studio-tab-${tab.toLowerCase()}"]`).text())
        .toContain(tab);
    }
  });

  it("restores tab and runId from the URL after a refresh", async () => {
    currentQuery = { tab: "run", runId: "108" };
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    expect(wrapper.find('[data-testid="run-setup-panel"]').exists()).toBe(true);
    const workspace = wrapper.findComponent(RunWorkspaceStub);
    expect(workspace.props("selectedRunId")).toBe(108);
  });

  it("keeps the selected run when moving from Run to downstream workflow tabs", async () => {
    currentQuery = { tab: "run", runId: "108" };
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    await wrapper.get('[data-testid="studio-tab-content"]').trigger("click");

    const content = wrapper.findComponent(childStubs.ContentWorkflowPanel);
    expect(content.props("runId")).toBe(108);
    expect(replace).toHaveBeenLastCalledWith({
      query: { tab: "content", runId: "108" }
    });
  });

  it("restores a downstream workflow tab with its runId after refresh", async () => {
    currentQuery = { tab: "publish", runId: "108" };
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    const publish = wrapper.findComponent(childStubs.PublishPanel);
    expect(publish.props("runId")).toBe(108);
  });

  it("falls back to the latest run reported by RunWorkspace for an invalid runId", async () => {
    currentQuery = { tab: "run", runId: "abc" };
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    const workspace = wrapper.findComponent(RunWorkspaceStub);
    workspace.vm.$emit("update:runId", 3);
    await flushPromises();

    expect(replace).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ tab: "run", runId: "3" })
      })
    );
    expect(wrapper.text()).toContain("已回退到最新 Run");
  });

  it("falls back to Brief for an unknown tab and shows a notice", async () => {
    currentQuery = { tab: "not-a-tab" };
    getCampaignMock.mockResolvedValue(campaign);
    const wrapper = mountStudio();
    await flushPromises();

    expect(wrapper.find('[data-testid="brief-panel"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("未知 Tab");
    expect(replace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: "brief" }) })
    );
  });

  it("keeps the 404 and invalid-id behavior from the old detail page", async () => {
    getCampaignMock.mockRejectedValue(
      new ApiError(404, "CAMPAIGN_NOT_FOUND", "campaign 7 was not found")
    );
    const notFound = mountStudio();
    await flushPromises();
    expect(notFound.get('[data-testid="studio-not-found"]').text()).toContain("不存在");

    currentParams = { campaignId: "zero" };
    const invalid = mountStudio();
    await flushPromises();
    expect(invalid.get('[data-testid="studio-invalid-id"]').text()).toContain("无效");
  });

  it("logs out and returns to login on 401", async () => {
    getCampaignMock.mockRejectedValue(
      new ApiError(401, "UNAUTHENTICATED", "authentication is required")
    );
    mountStudio();
    await flushPromises();

    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith(
      expect.objectContaining({ path: "/login" })
    );
  });
});
