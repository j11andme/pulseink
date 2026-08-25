import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  decideInsight,
  generateInsightCandidate,
  listInsightsByCampaign,
  searchApprovedInsights,
  type InsightResponse
} from "../../api/memory";
import { useAuthStore } from "../../stores/auth";
import InsightPanel from "./InsightPanel.vue";

vi.mock("../../api/memory", () => ({
  generateInsightCandidate: vi.fn(),
  listInsightsByCampaign: vi.fn(),
  decideInsight: vi.fn(),
  searchApprovedInsights: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const generateMock = vi.mocked(generateInsightCandidate);
const listMock = vi.mocked(listInsightsByCampaign);
const decideMock = vi.mocked(decideInsight);
const searchMock = vi.mocked(searchApprovedInsights);

function insight(overrides: Partial<InsightResponse> = {}): InsightResponse {
  return {
    id: 1,
    campaignId: 7,
    runId: 12,
    category: "CHANNEL_PATTERN",
    title: "Blog 需要事实引用",
    insightText: "高事实风险内容在 Blog 渠道需要引用。",
    scopeType: "CAMPAIGN",
    scopeValue: "7",
    applicableChannels: ["BLOG"],
    evidenceRefs: [{ contentVersionId: 51, publicationId: 9, metricFrom: null, metricTo: null }],
    confidence: 0.82,
    limitations: ["样本较小", "仅一次 Campaign"],
    status: "PENDING",
    indexStatus: "NOT_INDEXED",
    createdBy: 1,
    reviewedBy: null,
    reviewComment: null,
    createdAt: "2026-08-04T12:00:00Z",
    reviewedAt: null,
    indexedAt: null,
    ...overrides
  };
}

function mountPanel(runId: number | null = 12) {
  return mount(InsightPanel, {
    props: { runId, campaignId: 7 }
  });
}

describe("InsightPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    generateMock.mockReset();
    listMock.mockReset().mockResolvedValue([]);
    decideMock.mockReset();
    searchMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("generates a PENDING candidate only on explicit user action", async () => {
    const pending = insight();
    listMock.mockResolvedValue([pending]);
    let resolveGenerate!: (value: InsightResponse) => void;
    generateMock.mockImplementation(
      () => new Promise((resolve) => {
        resolveGenerate = resolve;
      }) as never
    );
    const wrapper = mountPanel();
    await flushPromises();
    expect(generateMock).not.toHaveBeenCalled();

    await wrapper.get('[data-testid="insight-generate"]').trigger("click");
    await wrapper.get('[data-testid="insight-generate"]').trigger("click");

    expect(generateMock).toHaveBeenCalledTimes(1);
    expect(generateMock).toHaveBeenCalledWith("signed.jwt", 12);
    expect(wrapper.get('[data-testid="insight-generate"]').attributes("disabled")).toBeDefined();

    resolveGenerate(pending);
    await flushPromises();
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  it("shows evidence, limitations, confidence and index status", async () => {
    listMock.mockResolvedValue([insight()]);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.text()).toContain("Blog 需要事实引用");
    expect(wrapper.text()).toContain("高事实风险内容");
    expect(wrapper.text()).toContain("样本较小");
    expect(wrapper.text()).toContain("0.82");
    expect(wrapper.text()).toContain("NOT_INDEXED");
    expect(wrapper.text()).toContain("contentVersionId 51");
  });

  it("allows APPROVE/REJECT only for PENDING candidates and keeps decided ones read-only", async () => {
    listMock.mockResolvedValue([insight(), insight({
      id: 2, title: "已批准洞察", status: "APPROVED", indexStatus: "INDEXED"
    })]);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.findAll('[data-testid="insight-decision"]')).toHaveLength(1);
    expect(wrapper.text()).toContain("已批准洞察");
    expect(wrapper.text()).toContain("INDEXED");

    decideMock.mockResolvedValue(insight({ status: "APPROVED", indexStatus: "INDEXING" }));
    await wrapper.get('[data-testid="insight-decision"]').setValue("APPROVE");
    await wrapper.get('[data-testid="insight-decide-submit"]').trigger("click");
    await flushPromises();

    expect(decideMock).toHaveBeenCalledWith("signed.jwt", 1, "APPROVE", undefined);
  });

  it("approval starts a bounded refresh that never fakes success for a failed index", async () => {
    vi.useFakeTimers();
    listMock
      .mockResolvedValueOnce([insight()])
      .mockResolvedValueOnce([insight({ status: "APPROVED", indexStatus: "INDEXING" })])
      .mockResolvedValue([insight({ status: "APPROVED", indexStatus: "FAILED" })]);
    decideMock.mockResolvedValue(insight({ status: "APPROVED", indexStatus: "INDEXING" }));
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-testid="insight-decision"]').setValue("APPROVE");
    await wrapper.get('[data-testid="insight-decide-submit"]').trigger("click");
    await flushPromises();

    await vi.advanceTimersByTimeAsync(4000);
    expect(wrapper.text()).toContain("FAILED");
    expect(wrapper.text()).not.toContain("INDEXED");

    wrapper.unmount();
    const calls = listMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(listMock.mock.calls.length).toBe(calls);
    vi.useRealTimers();
  });

  it("searches only backend-returned approved insights", async () => {
    listMock.mockResolvedValue([]);
    searchMock.mockResolvedValue([{
      insightId: 5, sourceCampaignId: 3, title: "历史洞察", insightText: "只展示已批准结果",
      category: "CHANNEL_PATTERN", scopeType: "WORKSPACE", scopeValue: "",
      applicableChannels: ["BLOG"], confidence: 0.75, approvedAt: "2026-08-01T12:00:00Z"
    }]);
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-testid="insight-search-input"]').setValue("渠道规律");
    await wrapper.get('[data-testid="insight-search-submit"]').trigger("click");
    await flushPromises();

    expect(searchMock).toHaveBeenCalledWith("signed.jwt", "渠道规律", undefined, 0);
    expect(wrapper.text()).toContain("历史洞察");
    expect(wrapper.text()).toContain("只展示已批准结果");
  });

  it("keeps viewers read-only and prompts without a run", () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const viewer = mountPanel();
    expect(viewer.find('[data-testid="insight-generate"]').exists()).toBe(false);
    expect(viewer.find('[data-testid="insight-decide-submit"]').exists()).toBe(false);
    expect(viewer.text()).toContain("Viewer 只读");

    const noRun = mountPanel(null);
    expect(noRun.get('[data-testid="insight-no-run"]').text()).toContain("请先启动 Run");
  });
});
