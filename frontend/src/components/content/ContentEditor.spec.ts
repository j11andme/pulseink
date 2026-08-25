import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  approveContentVersion,
  createContentVersion,
  type ContentItemResponse
} from "../../api/content";
import { ApiError } from "../../api/http";
import { useAuthStore } from "../../stores/auth";
import ContentEditor from "./ContentEditor.vue";

vi.mock("../../api/content", () => ({
  createContentVersion: vi.fn(),
  approveContentVersion: vi.fn()
}));

const createMock = vi.mocked(createContentVersion);
const approveMock = vi.mocked(approveContentVersion);

const item: ContentItemResponse = {
  id: 5,
  runId: 12,
  taskId: "creator",
  currentVersionNo: 2,
  itemVersion: 4,
  createdAt: "2026-08-04T12:00:00Z",
  updatedAt: "2026-08-04T12:05:00Z",
  versions: [
    {
      id: 52, versionNo: 2, content: { title: "当前内容" }, sourceRefs: ["doc-1"],
      origin: "HUMAN", sourceArtifactId: null, sourceArtifactVersion: null,
      sourceArtifactStatus: null, createdBy: 1, createdAt: "2026-08-04T12:05:00Z"
    },
    {
      id: 51, versionNo: 1, content: { title: "旧内容" }, sourceRefs: ["doc-1"],
      origin: "AGENT", sourceArtifactId: "run-12-draft-v1",
      sourceArtifactVersion: 1, sourceArtifactStatus: "VALID",
      createdBy: null, createdAt: "2026-08-04T12:04:00Z"
    }
  ],
  approvals: [{
    id: 8, contentVersionId: 51, actorUserId: 1, comment: "同意", createdAt: "2026-08-04T12:06:00Z"
  }]
};

function mountEditor() {
  return mount(ContentEditor, {
    props: { item, runId: 12 }
  });
}

describe("ContentEditor", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    createMock.mockReset();
    approveMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("maps a valid JSON Object to a human version and keeps sourceRefs", async () => {
    createMock.mockResolvedValue({
      id: 53, versionNo: 3, content: { title: "人工新版本" }, sourceRefs: ["doc-1"],
      origin: "HUMAN", sourceArtifactId: null, sourceArtifactVersion: null,
      sourceArtifactStatus: null, createdBy: 1, createdAt: "2026-08-04T12:07:00Z"
    });
    const wrapper = mountEditor();

    await wrapper.get('[data-testid="content-edit-textarea"]')
      .setValue('{"title":"人工新版本","body":"正文"}');
    await wrapper.get('[data-testid="content-create-version"]').trigger("click");
    await flushPromises();

    expect(createMock).toHaveBeenCalledWith("signed.jwt", 5, {
      expectedCurrentVersionNo: 2,
      expectedItemVersion: 4,
      content: { title: "人工新版本", body: "正文" },
      sourceRefs: ["doc-1"]
    });
    expect(wrapper.emitted("changed")).toBeTruthy();
  });

  it("rejects empty and non-object JSON before submitting", async () => {
    const wrapper = mountEditor();
    const textarea = wrapper.get('[data-testid="content-edit-textarea"]');

    await textarea.setValue("");
    await wrapper.get('[data-testid="content-create-version"]').trigger("click");
    await textarea.setValue("[1,2]");
    await wrapper.get('[data-testid="content-create-version"]').trigger("click");
    await textarea.setValue('"text"');
    await wrapper.get('[data-testid="content-create-version"]').trigger("click");

    expect(createMock).not.toHaveBeenCalled();
    expect(wrapper.get('[data-testid="content-edit-error"]').text()).toContain("JSON Object");
  });

  it("preserves the editing text on a 409 conflict and emits changed for reload", async () => {
    createMock.mockRejectedValue(
      new ApiError(409, "CONTENT_VERSION_CONFLICT", "content version conflict")
    );
    const wrapper = mountEditor();

    await wrapper.get('[data-testid="content-edit-textarea"]').setValue('{"title":"冲突后保留"}');
    await wrapper.get('[data-testid="content-create-version"]').trigger("click");
    await flushPromises();

    expect((wrapper.get('[data-testid="content-edit-textarea"]').element as HTMLTextAreaElement).value)
      .toContain("冲突后保留");
    expect(wrapper.get('[data-testid="content-edit-error"]').text()).toContain("冲突");
    expect(wrapper.emitted("changed")).toBeTruthy();
  });

  it("approves a selected version with the latest optimistic-lock fields", async () => {
    approveMock.mockResolvedValue({
      id: 9, contentVersionId: 52, actorUserId: 1, comment: "可以发布", createdAt: "2026-08-04T12:08:00Z"
    });
    const wrapper = mountEditor();

    await wrapper.get('[data-testid="content-approve-version"]').setValue("52");
    await wrapper.get('[data-testid="content-approve-comment"]').setValue("可以发布");
    await wrapper.get('[data-testid="content-approve-submit"]').trigger("click");
    await flushPromises();

    expect(approveMock).toHaveBeenCalledWith("signed.jwt", 5, {
      contentVersionId: 52,
      expectedCurrentVersionNo: 2,
      expectedItemVersion: 4,
      comment: "可以发布"
    });
    expect(wrapper.emitted("changed")).toBeTruthy();
  });

  it("hides write controls for viewers", () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const wrapper = mountEditor();

    expect(wrapper.find('[data-testid="content-create-version"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="content-approve-submit"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("Viewer 只读");
  });

  it("resets editor and approval selection when the parent selects another content item", async () => {
    const wrapper = mountEditor();
    await wrapper.get('[data-testid="content-edit-textarea"]')
      .setValue('{"title":"未提交的旧条目"}');

    await wrapper.setProps({
      item: {
        ...item,
        id: 6,
        taskId: "creator-social",
        currentVersionNo: 1,
        versions: [{
          ...item.versions[0],
          id: 61,
          versionNo: 1,
          content: { title: "新的条目" }
        }],
        approvals: []
      }
    });

    expect((wrapper.get('[data-testid="content-edit-textarea"]')
      .element as HTMLTextAreaElement).value).toContain("新的条目");
    expect((wrapper.get('[data-testid="content-approve-version"]')
      .element as HTMLSelectElement).value).toBe("61");
  });
});
