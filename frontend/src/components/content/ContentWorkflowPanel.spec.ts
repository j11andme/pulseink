import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getRunContents, type RunContentsResponse } from "../../api/content";
import { useAuthStore } from "../../stores/auth";
import ContentWorkflowPanel from "./ContentWorkflowPanel.vue";

vi.mock("../../api/content", () => ({
  getRunContents: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const getRunContentsMock = vi.mocked(getRunContents);

const response: RunContentsResponse = {
  contents: [
    {
      id: 5,
      runId: 12,
      taskId: "creator",
      currentVersionNo: 2,
      itemVersion: 4,
      createdAt: "2026-08-04T12:00:00Z",
      updatedAt: "2026-08-04T12:05:00Z",
      versions: [
        {
          id: 52, versionNo: 2, content: { title: "新版" }, sourceRefs: ["doc-1"],
          origin: "HUMAN", sourceArtifactId: null, sourceArtifactVersion: null,
          sourceArtifactStatus: null, createdBy: 1, createdAt: "2026-08-04T12:05:00Z"
        },
        {
          id: 51, versionNo: 1, content: { title: "旧版" }, sourceRefs: ["doc-1"],
          origin: "AGENT", sourceArtifactId: "run-12-draft-v1",
          sourceArtifactVersion: 1, sourceArtifactStatus: "VALID",
          createdBy: null, createdAt: "2026-08-04T12:04:00Z"
        }
      ],
      approvals: [{
        id: 8, contentVersionId: 51, actorUserId: 1, comment: "同意", createdAt: "2026-08-04T12:06:00Z"
      }]
    }
  ],
  reviews: [{
    id: 3, sourceArtifactId: "run-12-draft-v1", sourceArtifactVersion: 1,
    sourceArtifactStatus: "VALID", passed: false, repairRound: 1,
    issues: [{ type: "MISSING_EVIDENCE", affectedTaskIds: ["creator"], message: "缺少证据" }],
    createdAt: "2026-08-04T12:04:30Z"
  }]
};

function mountPanel(runId: number | null = 12) {
  return mount(ContentWorkflowPanel, {
    props: { runId },
    global: {
      stubs: {
        ContentEditor: {
          name: "ContentEditor",
          props: ["item", "runId"],
          emits: ["changed"],
          template: '<div data-testid="content-editor-stub"><button type="button" @click="$emit(\'changed\')">emit-changed</button></div>'
        }
      }
    }
  });
}

describe("ContentWorkflowPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getRunContentsMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("loads content items, review reports and sorts versions newest first", async () => {
    getRunContentsMock.mockResolvedValue(response);
    const wrapper = mountPanel();
    await flushPromises();

    expect(getRunContentsMock).toHaveBeenCalledWith("signed.jwt", 12);
    expect(wrapper.text()).toContain("creator");
    expect(wrapper.text()).toContain("currentVersionNo 2");
    expect(wrapper.text()).toContain("MISSING_EVIDENCE");
    expect(wrapper.text()).toContain("缺少证据");
    const rendered = wrapper.text();
    expect(rendered.indexOf("新版")).toBeLessThan(rendered.indexOf("旧版"));
    expect(wrapper.find('[data-testid="content-editor-stub"]').exists()).toBe(true);
  });

  it("reloads after a content editor change event", async () => {
    getRunContentsMock.mockResolvedValue(response);
    const wrapper = mountPanel();
    await flushPromises();
    expect(getRunContentsMock).toHaveBeenCalledTimes(1);

    await wrapper.get('[data-testid="content-editor-stub"]').find("button").trigger("click");
    await flushPromises();
    expect(getRunContentsMock).toHaveBeenCalledTimes(2);
  });

  it("prompts to start a run before loading when runId is absent", () => {
    const wrapper = mountPanel(null);
    expect(wrapper.get('[data-testid="content-no-run"]').text()).toContain("请先启动 Run");
    expect(getRunContentsMock).not.toHaveBeenCalled();
  });

  it("shows loading, empty and error states", async () => {
    getRunContentsMock.mockResolvedValue({ contents: [], reviews: [] });
    const wrapper = mountPanel();
    expect(wrapper.get('[data-testid="content-loading"]').text()).toContain("加载");
    await flushPromises();
    expect(wrapper.get('[data-testid="content-empty"]').text()).toContain("暂无 Content");

    getRunContentsMock.mockRejectedValue(new Error("network down"));
    await wrapper.get('[data-testid="content-reload"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="content-error"]').text()).toContain("加载失败");
  });
});
