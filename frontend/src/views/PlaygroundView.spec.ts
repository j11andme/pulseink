import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../stores/auth";
import PlaygroundView from "./PlaygroundView.vue";

const push = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({ push }),
  RouterLink: {
    props: ["to"],
    template: '<a><slot /></a>'
  }
}));

describe("PlaygroundView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("submits a prompt and renders the streamed answer", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        [
          'event:started\ndata:{"requestId":"r1","provider":"fake","model":"pulseink-fake"}',
          'event:content_delta\ndata:{"requestId":"r1","content":"Pulse"}',
          'event:content_delta\ndata:{"requestId":"r1","content":"Ink"}',
          'event:completed\ndata:{"requestId":"r1","finishReason":"STOP"}',
          ""
        ].join("\n\n"),
        {
          status: 200,
          headers: { "Content-Type": "text/event-stream" }
        }
      )
    );
    const wrapper = mount(PlaygroundView);

    await wrapper.get('[data-testid="model-message"]').setValue(
      "为 Java 开发者介绍 PulseInk"
    );
    await wrapper.get('[data-testid="model-form"]').trigger("submit");
    await flushPromises();

    expect(wrapper.get('[data-testid="model-output"]').text()).toContain(
      "PulseInk"
    );
    expect(wrapper.get('[data-testid="stream-status"]').text()).toContain(
      "已完成"
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1]?.body as string)).toEqual(
      expect.objectContaining({ maxTokens: 4096 })
    );
  });

  it("logs out from the shared shell header", async () => {
    const wrapper = mount(PlaygroundView);

    await wrapper.get('[data-testid="logout"]').trigger("click");

    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith("/login");
  });
});
