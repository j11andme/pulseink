import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { startRun } from "../../api/run";
import type { CampaignResponse } from "../../api/campaign";
import { useAuthStore } from "../../stores/auth";
import RunSetupPanel from "./RunSetupPanel.vue";

vi.mock("../../api/run", () => ({
  startRun: vi.fn()
}));

const startRunMock = vi.mocked(startRun);

const campaign: CampaignResponse = {
  id: 7,
  name: "PulseInk 秋招发布",
  objective: "向 Java 后端开发者介绍 PulseInk",
  audience: "Java 开发者",
  channels: ["BLOG", "SOCIAL"],
  constraints: [],
  status: "DRAFT",
  createdBy: 1,
  version: 0,
  createdAt: "2026-08-04T12:00:00Z",
  updatedAt: "2026-08-04T12:00:00Z"
};

function mountPanel() {
  return mount(RunSetupPanel, {
    props: { campaign }
  });
}

describe("RunSetupPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    startRunMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("business templates generate legal task properties with locked channel count", async () => {
    const wrapper = mountPanel();

    await wrapper.get('[data-testid="run-template-multichannel"]').trigger("click");
    await wrapper.get('[data-testid="run-advanced-toggle"]').trigger("click");

    const channelInput = wrapper.get('[data-testid="task-channel-count"]');
    expect(channelInput.attributes("disabled")).toBeDefined();
    expect((channelInput.element as HTMLInputElement).value).toBe("2");

    const createdRun = {
      runId: 108, campaignId: 7, requestedPolicy: "ADAPTIVE" as const,
      selectedMode: "ORCHESTRATED" as const, selectorPolicyVersion: "selector-v1",
      reasonCodes: ["DECOMPOSABLE_OR_HIGH_RISK"],
      featureSnapshot: { channelCount: 2 }, estimatedTokenBudget: 20000,
      state: "CREATED", failureReason: null, startedAt: null, completedAt: null,
      createdAt: "2026-08-04T12:00:00Z", updatedAt: "2026-08-04T12:00:00Z"
    };
    startRunMock.mockResolvedValue(createdRun);
    await wrapper.get('[data-testid="run-submit"]').trigger("click");
    await flushPromises();

    expect(wrapper.emitted("run-created")?.[0]).toEqual([createdRun]);

    expect(startRunMock).toHaveBeenCalledTimes(1);
    expect(startRunMock.mock.calls[0][1]).toBe(7);
    expect(startRunMock.mock.calls[0][2]).toMatchObject({
      requestedPolicy: "ADAPTIVE",
      taskProperties: {
        channelCount: 2,
        decomposability: 0.8,
        sourceDiversity: 3,
        parallelResearchBranches: 3,
        sequentialDependency: 0.4,
        factualRisk: 0.8,
        toolBreadth: 3,
        latencyBudgetMs: 20000
      }
    });
  });

  it("quick draft explicitly requests DIRECT even for a multi-channel Campaign", async () => {
    const wrapper = mountPanel();
    await wrapper.get('[data-testid="run-template-quick"]').trigger("click");
    startRunMock.mockResolvedValue({
      runId: 109, campaignId: 7, requestedPolicy: "DIRECT",
      selectedMode: "DIRECT", selectorPolicyVersion: "selector-v1",
      reasonCodes: ["MANUAL_POLICY_OVERRIDE"],
      featureSnapshot: { channelCount: 2 }, estimatedTokenBudget: 8000,
      state: "CREATED", failureReason: null, startedAt: null, completedAt: null,
      createdAt: "2026-08-04T12:00:00Z", updatedAt: "2026-08-04T12:00:00Z"
    });

    await wrapper.get('[data-testid="run-submit"]').trigger("click");
    await flushPromises();

    expect(startRunMock.mock.calls[0][2]).toMatchObject({
      requestedPolicy: "DIRECT",
      taskProperties: {
        channelCount: 2,
        decomposability: 0.1,
        sourceDiversity: 0,
        parallelResearchBranches: 0,
        factualRisk: 0.1,
        toolBreadth: 0,
        latencyBudgetMs: 8000
      }
    });
  });

  it("sends only one request while pending and shows the backend selection result", async () => {
    let resolve!: (value: unknown) => void;
    startRunMock.mockImplementation(
      () => new Promise((resolveRun) => {
        resolve = resolveRun;
      }) as never
    );
    const wrapper = mountPanel();

    await wrapper.get('[data-testid="run-submit"]').trigger("click");
    await wrapper.get('[data-testid="run-submit"]').trigger("click");

    expect(startRunMock).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="run-submit"]').attributes("disabled")).toBeDefined();

    resolve({
      runId: 108, campaignId: 7, requestedPolicy: "ADAPTIVE",
      selectedMode: "REACT", selectorPolicyVersion: "selector-v1",
      reasonCodes: ["UNIFIED_CONTEXT_PREFERRED"], featureSnapshot: {},
      estimatedTokenBudget: 10000, state: "CREATED", failureReason: null,
      startedAt: null, completedAt: null,
      createdAt: "2026-08-04T12:00:00Z", updatedAt: "2026-08-04T12:00:00Z"
    });
    await flushPromises();
    expect(wrapper.text()).toContain("REACT");
    expect(wrapper.text()).toContain("UNIFIED_CONTEXT_PREFERRED");
  });

  it("rejects invalid probabilities and negative counts before any request", async () => {
    const wrapper = mountPanel();
    await wrapper.get('[data-testid="run-advanced-toggle"]').trigger("click");
    await wrapper.get('[data-testid="task-factual-risk"]').setValue("1.5");

    await wrapper.get('[data-testid="run-submit"]').trigger("click");

    expect(startRunMock).not.toHaveBeenCalled();
    expect(wrapper.get('[data-testid="run-error"]').text()).toContain("0—1");
  });

  it("keeps viewers read-only", () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const wrapper = mountPanel();

    expect(wrapper.find('[data-testid="run-submit"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("Viewer 只读");
  });
});
