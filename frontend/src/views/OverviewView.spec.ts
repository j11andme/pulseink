import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listCampaigns } from "../api/campaign";
import { useAuthStore } from "../stores/auth";
import OverviewView from "./OverviewView.vue";

vi.mock("../api/campaign", () => ({
  listCampaigns: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const listCampaignsMock = vi.mocked(listCampaigns);

function mountOverview() {
  return mount(OverviewView, {
    global: {
      stubs: {
        AppShell: { template: '<div class="app-shell-stub"><slot /></div>' },
        RouterLink: {
          props: ["to"],
          template: '<a :href="String(to)"><slot /></a>'
        }
      }
    }
  });
}

describe("OverviewView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    listCampaignsMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("shows the product position, campaign total and latest activity", async () => {
    listCampaignsMock.mockResolvedValue({
      items: [
        {
          id: 9,
          name: "PulseInk 秋招发布",
          objective: "objective",
          audience: "Java 开发者",
          channels: ["BLOG", "SOCIAL"],
          constraints: [],
          status: "DRAFT",
          createdBy: 1,
          version: 0,
          createdAt: "2026-08-04T12:00:00Z",
          updatedAt: "2026-08-04T12:00:00Z"
        }
      ],
      page: 0,
      size: 20,
      totalElements: 7,
      totalPages: 1
    });

    const wrapper = mountOverview();
    await flushPromises();

    expect(wrapper.text()).toContain("内容策划与运营");
    expect(wrapper.text()).toContain("7");
    expect(wrapper.text()).toContain("PulseInk 秋招发布");
    expect(wrapper.text()).toContain("2026-08-04 20:00:00");
    expect(wrapper.get('[data-testid="create-campaign-link"]').attributes("href"))
      .toBe("/campaigns");
  });

  it("renders the golden path flow without inventing run metrics", async () => {
    listCampaignsMock.mockResolvedValue({
      items: [], page: 0, size: 20, totalElements: 0, totalPages: 0
    });

    const wrapper = mountOverview();
    await flushPromises();

    for (const step of ["Brief", "Agent", "Review", "Publish", "Feedback", "Memory"]) {
      expect(wrapper.text()).toContain(step);
    }
    expect(wrapper.text()).not.toContain("Run 成功率");
    expect(wrapper.text()).not.toContain("Token 消耗");
  });

  it("hides the create entry for viewers and keeps it for editors", async () => {
    listCampaignsMock.mockResolvedValue({
      items: [], page: 0, size: 20, totalElements: 0, totalPages: 0
    });

    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const viewer = mountOverview();
    await flushPromises();
    expect(viewer.find('[data-testid="create-campaign-link"]').exists()).toBe(false);
    viewer.unmount();

    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
    const editor = mountOverview();
    await flushPromises();
    expect(editor.find('[data-testid="create-campaign-link"]').exists()).toBe(true);
  });

  it("shows the error state and reloads on retry", async () => {
    listCampaignsMock
      .mockRejectedValueOnce(new Error("network down"))
      .mockResolvedValueOnce({
        items: [], page: 0, size: 20, totalElements: 0, totalPages: 0
      });

    const wrapper = mountOverview();
    await flushPromises();

    expect(wrapper.get('[data-testid="overview-error"]').text())
      .toContain("加载失败，请稍后重试");
    await wrapper.get('[data-testid="overview-retry"]').trigger("click");
    await flushPromises();
    expect(listCampaignsMock).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="overview-error"]').exists()).toBe(false);
  });
});
