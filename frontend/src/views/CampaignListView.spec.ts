import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../stores/auth";
import CampaignListView from "./CampaignListView.vue";

const push = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({ push }),
  RouterLink: {
    props: ["to"],
    template: '<a><slot /></a>'
  }
}));

function mockCampaignList(items: unknown[]) {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        items,
        page: 0,
        size: 20,
        totalElements: items.length,
        totalPages: items.length === 0 ? 0 : 1
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    )
  );
}

function mockCreateSuccess() {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        id: 99,
        name: "New",
        objective: "o",
        audience: "a",
        channels: ["BLOG"],
        constraints: [],
        status: "DRAFT",
        createdBy: 1,
        version: 0,
        createdAt: "2026-08-06T10:00:00Z",
        updatedAt: "2026-08-06T10:00:00Z"
      }),
      { status: 201, headers: { "Content-Type": "application/json" } }
    )
  );
}

function mockValidationError(message: string) {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({ code: "INVALID_CAMPAIGN", message }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    )
  );
}

describe("CampaignListView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("sends a bearer token and renders loading then populated list", async () => {
    const fetchMock = mockCampaignList([
      {
        id: 1,
        name: "First Campaign",
        objective: "o",
        audience: "Java devs",
        channels: ["BLOG", "SOCIAL"],
        constraints: [],
        status: "DRAFT",
        createdBy: 1,
        version: 0,
        createdAt: "2026-08-06T10:00:00Z",
        updatedAt: "2026-08-06T10:00:00Z"
      }
    ]);
    const wrapper = mount(CampaignListView, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
    });

    expect(wrapper.get('[data-testid="loading"]').text()).toContain("正在加载");
    await flushPromises();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const fetchCall = fetchMock.mock.calls[0];
    expect(fetchCall[0]).toBe("/api/campaigns?page=0&size=20");
    const headers = new Headers((fetchCall[1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBe("Bearer signed.jwt");
    expect(wrapper.get('[data-testid="campaign-list"]').text()).toContain(
      "First Campaign"
    );
  });

  it("renders an empty state when there are no campaigns", async () => {
    mockCampaignList([]);
    const wrapper = mount(CampaignListView);

    await flushPromises();

    expect(wrapper.get('[data-testid="empty"]').text()).toContain("暂无 Campaign");
  });

  it("submits the mapped request once and refreshes the list", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          items: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          id: 99,
          name: "New Campaign",
          objective: "o",
          audience: "a",
          channels: ["BLOG"],
          constraints: [],
          status: "DRAFT",
          createdBy: 1,
          version: 0,
          createdAt: "2026-08-06T10:00:00Z",
          updatedAt: "2026-08-06T10:00:00Z"
        }),
        { status: 201, headers: { "Content-Type": "application/json" } }
      )
    );
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          items: [
            {
              id: 99,
              name: "New Campaign",
              objective: "o",
              audience: "a",
              channels: ["BLOG"],
              constraints: [],
              status: "DRAFT",
              createdBy: 1,
              version: 0,
              createdAt: "2026-08-06T10:00:00Z",
              updatedAt: "2026-08-06T10:00:00Z"
            }
          ],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );

    const wrapper = mount(CampaignListView);
    await flushPromises();

    await wrapper.get("button.primary-button").trigger("click");

    await wrapper.get("button.primary-button").trigger("click");
    await wrapper.get('[data-testid="form-name"]').setValue("New Campaign");
    await wrapper.get('[data-testid="form-objective"]').setValue("o");
    await wrapper.get('[data-testid="form-audience"]').setValue("a");
    const checkbox = wrapper
      .get('[data-testid="form-channels"]')
      .findAll("input[type=checkbox]")[0];
    await checkbox.setValue(true);
    await wrapper.get('[data-testid="form-submit"]').trigger("submit");
    await flushPromises();

    const createCall = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(createCall[0]).toBe("/api/campaigns");
    expect(JSON.parse(createCall[1].body as string)).toEqual({
      name: "New Campaign",
      objective: "o",
      audience: "a",
      channels: ["BLOG"],
      constraints: []
    });
    expect(wrapper.get('[data-testid="campaign-list"]').text()).toContain(
      "New Campaign"
    );
  });

  it("hides the create control for VIEWER", async () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    mockCampaignList([]);
    const wrapper = mount(CampaignListView);

    await flushPromises();

    expect(wrapper.find("button.primary-button").exists()).toBe(false);
  });

  it("shows backend validation message and keeps the form editable", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          items: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "INVALID_CAMPAIGN",
          message: "campaign name must not be blank"
        }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      )
    );

    const wrapper = mount(CampaignListView);
    await flushPromises();

    await wrapper.get("button.primary-button").trigger("click");
    await wrapper.get('[data-testid="form-name"]').setValue("Name");
    await wrapper.get('[data-testid="form-objective"]').setValue("Objective");
    await wrapper.get('[data-testid="form-audience"]').setValue("Audience");
    await wrapper
      .get('[data-testid="form-channels"]')
      .findAll("input[type=checkbox]")[0]
      .setValue(true);
    await wrapper.get('[data-testid="form-submit"]').trigger("submit");
    await flushPromises();

    expect(wrapper.get('[data-testid="form-error"]').text()).toContain(
      "campaign name must not be blank"
    );
    expect(wrapper.find('[data-testid="form-name"]').exists()).toBe(true);
  });

  it("rejects blank objective and audience before sending create request", async () => {
    const fetchMock = mockCampaignList([]);
    const wrapper = mount(CampaignListView);
    await flushPromises();

    await wrapper.get("button.primary-button").trigger("click");
    await wrapper.get('[data-testid="form-name"]').setValue("Campaign");
    await wrapper
      .get('[data-testid="form-channels"]')
      .findAll("input[type=checkbox]")[0]
      .setValue(true);
    await wrapper.get('[data-testid="form-submit"]').trigger("submit");

    expect(wrapper.get('[data-testid="form-error"]').text()).toContain("目标");
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await wrapper.get('[data-testid="form-objective"]').setValue("Objective");
    await wrapper.get('[data-testid="form-submit"]').trigger("submit");

    expect(wrapper.get('[data-testid="form-error"]').text()).toContain("受众");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("logs out and redirects when create returns 401", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          items: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    );
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "UNAUTHENTICATED",
          message: "authentication is required"
        }),
        { status: 401, headers: { "Content-Type": "application/json" } }
      )
    );

    const wrapper = mount(CampaignListView);
    await flushPromises();
    await wrapper.get("button.primary-button").trigger("click");
    await wrapper.get('[data-testid="form-name"]').setValue("Campaign");
    await wrapper.get('[data-testid="form-objective"]').setValue("Objective");
    await wrapper.get('[data-testid="form-audience"]').setValue("Audience");
    await wrapper
      .get('[data-testid="form-channels"]')
      .findAll("input[type=checkbox]")[0]
      .setValue(true);
    await wrapper.get('[data-testid="form-submit"]').trigger("submit");
    await flushPromises();

    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith({
      path: "/login",
      query: { redirect: "/campaigns" }
    });
  });
});
