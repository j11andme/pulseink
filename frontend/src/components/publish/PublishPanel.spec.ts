import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getRunContents, type RunContentsResponse } from "../../api/content";
import {
  getPublication,
  listPublicationsByRun,
  publishContent,
  returnPublicationToEditing,
  type PublicationResponse
} from "../../api/publication";
import { useAuthStore } from "../../stores/auth";
import PublishPanel from "./PublishPanel.vue";

vi.mock("../../api/content", () => ({
  getRunContents: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

vi.mock("../../api/publication", () => ({
  publishContent: vi.fn(),
  listPublicationsByRun: vi.fn(),
  getPublication: vi.fn(),
  returnPublicationToEditing: vi.fn()
}));

const getRunContentsMock = vi.mocked(getRunContents);
const publishMock = vi.mocked(publishContent);
const listPublicationsMock = vi.mocked(listPublicationsByRun);
const getPublicationMock = vi.mocked(getPublication);
const returnToEditingMock = vi.mocked(returnPublicationToEditing);

const contents: RunContentsResponse = {
  contents: [{
    id: 5,
    runId: 12,
    taskId: "creator",
    currentVersionNo: 2,
    itemVersion: 4,
    createdAt: "2026-08-04T12:00:00Z",
    updatedAt: "2026-08-04T12:05:00Z",
    versions: [
      {
        id: 52, versionNo: 2, content: { title: "未批准版本" }, sourceRefs: [],
        origin: "AGENT", sourceArtifactId: "run-12-draft-v2", sourceArtifactVersion: 2,
        sourceArtifactStatus: "VALID", createdBy: null, createdAt: "2026-08-04T12:05:00Z"
      },
      {
        id: 51, versionNo: 1, content: { title: "已批准版本" }, sourceRefs: ["doc-1"],
        origin: "AGENT", sourceArtifactId: "run-12-draft-v1", sourceArtifactVersion: 1,
        sourceArtifactStatus: "VALID", createdBy: null, createdAt: "2026-08-04T12:04:00Z"
      }
    ],
    approvals: [{
      id: 8, contentVersionId: 51, actorUserId: 1, comment: "同意", createdAt: "2026-08-04T12:06:00Z"
    }]
  }],
  reviews: []
};

function mountPanel(runId: number | null = 12) {
  return mount(PublishPanel, {
    props: { runId, campaignChannels: ["BLOG", "SOCIAL"] }
  });
}

describe("PublishPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getRunContentsMock.mockReset().mockResolvedValue(contents);
    publishMock.mockReset();
    listPublicationsMock.mockReset().mockResolvedValue([]);
    getPublicationMock.mockReset();
    returnToEditingMock.mockReset().mockResolvedValue(undefined);
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("only offers versions that already have an Approval", async () => {
    const wrapper = mountPanel();
    await flushPromises();

    const options = wrapper.get('[data-testid="publish-version-select"]')
      .findAll("option")
      .map((option) => option.attributes("value"));
    expect(options).toContain("51");
    expect(options).not.toContain("52");
  });

  it("selects the latest current version when it is approved", async () => {
    const item = contents.contents[0];
    getRunContentsMock.mockResolvedValue({
      ...contents,
      contents: [{
        ...item,
        versions: [...item.versions].reverse(),
        approvals: [
          ...item.approvals,
          {
            id: 9,
            contentVersionId: 52,
            actorUserId: 1,
            comment: "修正后通过",
            createdAt: "2026-08-04T12:07:00Z"
          }
        ]
      }]
    });

    const wrapper = mountPanel();
    await flushPromises();

    expect((wrapper.get('[data-testid="publish-version-select"]')
      .element as HTMLSelectElement).value).toBe("52");
  });

  it("publishes the approved version once and disables while pending", async () => {
    let resolvePublish!: (value: PublicationResponse) => void;
    publishMock.mockImplementation(
      () => new Promise((resolve) => {
        resolvePublish = resolve;
      }) as never
    );
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-testid="publish-version-select"]').setValue("51");
    await wrapper.get('[data-testid="publish-channel-select"]').setValue("BLOG");
    await wrapper.get('[data-testid="publish-submit"]').trigger("click");
    await wrapper.get('[data-testid="publish-submit"]').trigger("click");

    expect(publishMock).toHaveBeenCalledTimes(1);
    expect(publishMock).toHaveBeenCalledWith("signed.jwt", 5, 51, "BLOG");
    expect(wrapper.get('[data-testid="publish-submit"]').attributes("disabled")).toBeDefined();

    resolvePublish({
      id: 9, runId: 12, contentVersionId: 51, channel: "BLOG",
      idempotencyKey: "uuid-1", status: "SENDING", attemptCount: 1,
      externalPostId: null, failureCode: null, failureMessage: null,
      createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:06:00Z",
      publishedAt: null
    });
    await flushPromises();
    expect(wrapper.text()).toContain("SENDING");
  });

  it("polls with a bounded interval and shows the PUBLISHED receipt", async () => {
    vi.useFakeTimers();
    publishMock.mockResolvedValue({
      id: 9, runId: 12, contentVersionId: 51, channel: "BLOG",
      idempotencyKey: "uuid-1", status: "PENDING", attemptCount: 1,
      externalPostId: null, failureCode: null, failureMessage: null,
      createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:06:00Z",
      publishedAt: null
    });
    getPublicationMock
      .mockResolvedValueOnce({
        id: 9, runId: 12, contentVersionId: 51, channel: "BLOG",
        idempotencyKey: "uuid-1", status: "SENDING", attemptCount: 1,
        externalPostId: null, failureCode: null, failureMessage: null,
        createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:06:00Z",
        publishedAt: null
      })
      .mockResolvedValue({
        id: 9, runId: 12, contentVersionId: 51, channel: "BLOG",
        idempotencyKey: "uuid-1", status: "PUBLISHED", attemptCount: 1,
        externalPostId: "post-ext-9", failureCode: null, failureMessage: null,
        createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:07:00Z",
        publishedAt: "2026-08-04T12:07:00Z"
      });
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-testid="publish-version-select"]').setValue("51");
    await wrapper.get('[data-testid="publish-submit"]').trigger("click");
    await flushPromises();

    await vi.advanceTimersByTimeAsync(4000);
    expect(getPublicationMock).toHaveBeenCalled();
    expect(wrapper.text()).toContain("PUBLISHED");
    expect(wrapper.text()).toContain("post-ext-9");

    wrapper.unmount();
    const calls = getPublicationMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getPublicationMock.mock.calls.length).toBe(calls);
    vi.useRealTimers();
  });

  it("resumes polling non-terminal publications after the page is restored", async () => {
    vi.useFakeTimers();
    listPublicationsMock.mockResolvedValue([{
      id: 10, runId: 12, contentVersionId: 51, channel: "BLOG",
      idempotencyKey: "uuid-restored", status: "SENDING", attemptCount: 1,
      externalPostId: null, failureCode: null, failureMessage: null,
      createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:06:00Z",
      publishedAt: null
    }]);
    getPublicationMock.mockResolvedValue({
      id: 10, runId: 12, contentVersionId: 51, channel: "BLOG",
      idempotencyKey: "uuid-restored", status: "PUBLISHED", attemptCount: 1,
      externalPostId: "post-ext-10", failureCode: null, failureMessage: null,
      createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:07:00Z",
      publishedAt: "2026-08-04T12:07:00Z"
    });

    const wrapper = mountPanel();
    await flushPromises();
    await vi.advanceTimersByTimeAsync(2000);

    expect(getPublicationMock).toHaveBeenCalledWith("signed.jwt", 10);
    expect(wrapper.text()).toContain("post-ext-10");

    wrapper.unmount();
    vi.useRealTimers();
  });

  it("shows FAILED publications with the backend-cleaned failure text", async () => {
    listPublicationsMock.mockResolvedValue([{
      id: 10, runId: 12, contentVersionId: 51, channel: "SOCIAL",
      idempotencyKey: "uuid-2", status: "FAILED", attemptCount: 3,
      externalPostId: null, failureCode: "CHANNEL_UNAVAILABLE",
      failureMessage: "channel sandbox is unavailable", createdAt: "2026-08-04T12:06:00Z",
      updatedAt: "2026-08-04T12:08:00Z", publishedAt: null
    }]);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.text()).toContain("FAILED");
    expect(wrapper.text()).toContain("CHANNEL_UNAVAILABLE");
    expect(wrapper.text()).toContain("channel sandbox is unavailable");

    await wrapper.get('[data-testid="publication-return-to-editing-10"]').trigger("click");
    await flushPromises();

    expect(returnToEditingMock).toHaveBeenCalledWith("signed.jwt", 10);
    expect(wrapper.emitted("return-to-editing")).toEqual([[12]]);
  });

  it("keeps viewers read-only and prompts without a run", async () => {
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const viewer = mountPanel();
    await flushPromises();
    expect(viewer.find('[data-testid="publish-submit"]').exists()).toBe(false);
    expect(viewer.text()).toContain("Viewer 只读");

    const noRun = mountPanel(null);
    expect(noRun.get('[data-testid="publish-no-run"]').text()).toContain("请先启动 Run");
  });

  it("switches to an approved version that belongs to the selected content item", async () => {
    getRunContentsMock.mockResolvedValue({
      ...contents,
      contents: [
        ...contents.contents,
        {
          ...contents.contents[0],
          id: 6,
          taskId: "creator-social",
          versions: [{
            ...contents.contents[0].versions[1],
            id: 61,
            versionNo: 1
          }],
          approvals: [{
            id: 9,
            contentVersionId: 61,
            actorUserId: 1,
            comment: "同意",
            createdAt: "2026-08-04T12:06:00Z"
          }]
        }
      ]
    });
    publishMock.mockResolvedValue({
      id: 11, runId: 12, contentVersionId: 61, channel: "BLOG",
      idempotencyKey: "uuid-3", status: "PUBLISHED", attemptCount: 1,
      externalPostId: "post-ext-11", failureCode: null, failureMessage: null,
      createdAt: "2026-08-04T12:06:00Z", updatedAt: "2026-08-04T12:06:00Z",
      publishedAt: "2026-08-04T12:06:00Z"
    });
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-testid="publish-content-select"]').setValue("6");
    expect((wrapper.get('[data-testid="publish-version-select"]')
      .element as HTMLSelectElement).value).toBe("61");

    await wrapper.get('[data-testid="publish-submit"]').trigger("click");
    await flushPromises();
    expect(publishMock).toHaveBeenCalledWith("signed.jwt", 6, 61, "BLOG");
  });
});
