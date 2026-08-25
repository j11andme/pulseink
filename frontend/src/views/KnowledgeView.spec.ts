import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  listKnowledgeDocuments,
  retryKnowledgeDocument,
  searchKnowledge,
  uploadKnowledgeDocument
} from "../api/knowledge";
import { useAuthStore } from "../stores/auth";
import KnowledgeView from "./KnowledgeView.vue";

vi.mock("../api/knowledge", () => ({
  listKnowledgeDocuments: vi.fn(),
  uploadKnowledgeDocument: vi.fn(),
  retryKnowledgeDocument: vi.fn(),
  searchKnowledge: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const listMock = vi.mocked(listKnowledgeDocuments);
const uploadMock = vi.mocked(uploadKnowledgeDocument);
const retryMock = vi.mocked(retryKnowledgeDocument);
const searchMock = vi.mocked(searchKnowledge);

function emptyPage() {
  return { total: 0, items: [] };
}

function mountKnowledge() {
  return mount(KnowledgeView, {
    global: {
      stubs: {
        AppShell: { template: '<div><slot /></div>' }
      }
    }
  });
}

function editorSession() {
  useAuthStore().acceptSession({
    accessToken: "signed.jwt",
    expiresIn: 1800,
    user: { id: 1, username: "demo", role: "EDITOR" }
  });
}

describe("KnowledgeView", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    listMock.mockReset().mockResolvedValue(emptyPage());
    uploadMock.mockReset();
    retryMock.mockReset();
    searchMock.mockReset();
  });

  it("uploads multipart parameters and disables the submit button while pending", async () => {
    editorSession();
    let resolveUpload!: (value: unknown) => void;
    uploadMock.mockImplementation(
      () => new Promise((resolve) => {
        resolveUpload = resolve;
      }) as never
    );
    const wrapper = mountKnowledge();
    await flushPromises();

    const file = new File(["# 产品资料"], "product.md", { type: "text/markdown" });
    const input = wrapper.get('[data-testid="knowledge-file"]');
    Object.defineProperty(input.element, "files", { value: [file] });
    await input.trigger("change");
    await wrapper.get('[data-testid="knowledge-type"]').setValue("PRODUCT");
    await wrapper.get('[data-testid="knowledge-authority"]').setValue("OFFICIAL");

    await wrapper.get('[data-testid="knowledge-upload-submit"]').trigger("click");

    expect(uploadMock).toHaveBeenCalledWith("signed.jwt", file, "PRODUCT", "OFFICIAL");
    expect(
      wrapper.get('[data-testid="knowledge-upload-submit"]').attributes("disabled")
    ).toBeDefined();

    resolveUpload({
      documentId: 4,
      sourceId: "doc-product-4",
      jobId: "job-4",
      status: "PENDING"
    });
    await flushPromises();
    expect(wrapper.text()).toContain("doc-product-4");
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  it("renders processing/failed states, filters, pagination and retry", async () => {
    editorSession();
    const activeItem = {
      documentId: 1, sourceId: "doc-1", originalFilename: "a.md",
      declaredMimeType: "text/markdown", detectedMimeType: "text/markdown",
      sizeBytes: 10, knowledgeType: "PRODUCT", authority: "OFFICIAL",
      documentVersion: 1, status: "ACTIVE", chunkCount: 2,
      failureCode: null, createdAt: "2026-08-04T12:00:00Z",
      updatedAt: "2026-08-04T12:00:00Z"
    };
    const failedItem = {
      documentId: 2, sourceId: "doc-2", originalFilename: "b.md",
      declaredMimeType: "text/markdown", detectedMimeType: "text/markdown",
      sizeBytes: 10, knowledgeType: "PRODUCT", authority: "OFFICIAL",
      documentVersion: 1, status: "FAILED", chunkCount: 0,
      failureCode: "PARSE_FAILED", createdAt: "2026-08-04T12:00:00Z",
      updatedAt: "2026-08-04T12:00:00Z"
    };
    const processingItem = {
      documentId: 3, sourceId: "doc-3", originalFilename: "c.md",
      declaredMimeType: "text/markdown", detectedMimeType: "text/markdown",
      sizeBytes: 10, knowledgeType: "PRODUCT", authority: "OFFICIAL",
      documentVersion: 1, status: "PROCESSING", chunkCount: 0,
      failureCode: null, createdAt: "2026-08-04T12:00:00Z",
      updatedAt: "2026-08-04T12:00:00Z"
    };
    listMock.mockImplementation(async (_token, filters) => ({
      total: 45,
      items: filters?.status === "FAILED"
        ? [failedItem]
        : [activeItem, failedItem, processingItem]
    }));
    const wrapper = mountKnowledge();
    await flushPromises();

    expect(wrapper.text()).toContain("ACTIVE");
    expect(wrapper.text()).toContain("FAILED");
    expect(wrapper.text()).toContain("PROCESSING");
    expect(wrapper.text()).toContain("PARSE_FAILED");

    await wrapper.get('[data-testid="knowledge-status-filter"]').setValue("FAILED");
    expect(listMock).toHaveBeenLastCalledWith("signed.jwt", {
      status: "FAILED", type: undefined, page: 0, size: 20
    });

    await wrapper.get('[data-testid="knowledge-page-next"]').trigger("click");
    expect(listMock).toHaveBeenLastCalledWith("signed.jwt", {
      status: "FAILED", type: undefined, page: 1, size: 20
    });

    retryMock.mockResolvedValue(undefined);
    await wrapper.get('[data-testid="knowledge-retry-document"]').trigger("click");
    expect(retryMock).toHaveBeenCalledWith("signed.jwt", 2);
    expect(listMock).toHaveBeenCalled();
  });

  it("polls with a bounded interval while a document is processing and clears it", async () => {
    vi.useFakeTimers();
    editorSession();
    listMock.mockResolvedValue({
      total: 1,
      items: [{
        documentId: 1, sourceId: "doc-1", originalFilename: "a.md",
        declaredMimeType: null, detectedMimeType: null, sizeBytes: 10,
        knowledgeType: "PRODUCT", authority: "OFFICIAL", documentVersion: 1,
        status: "PROCESSING", chunkCount: 0, failureCode: null,
        createdAt: "2026-08-04T12:00:00Z", updatedAt: "2026-08-04T12:00:00Z"
      }]
    });
    const wrapper = mountKnowledge();
    await flushPromises();
    const initialCalls = listMock.mock.calls.length;

    await vi.advanceTimersByTimeAsync(2000);
    expect(listMock.mock.calls.length).toBeGreaterThan(initialCalls);

    wrapper.unmount();
    const afterUnmount = listMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(listMock.mock.calls.length).toBe(afterUnmount);
    vi.useRealTimers();
  });

  it("shows real search-test retrieval mode, degraded reason and evidence", async () => {
    editorSession();
    searchMock.mockResolvedValue({
      retrievalMode: "RRF_HYBRID",
      degradedReasonCode: "KNN_UNAVAILABLE",
      evidence: [{
        sourceId: "doc-1", title: "产品规格", heading: "规格",
        snippet: "PulseInk 支持多渠道", score: 0.93,
        channels: ["BLOG"], type: "PRODUCT", authority: "OFFICIAL",
        updatedAt: "2026-08-04T12:00:00Z"
      }]
    });
    const wrapper = mountKnowledge();
    await flushPromises();

    await wrapper.get('[data-testid="knowledge-search-input"]').setValue("PulseInk 渠道");
    await wrapper.get('[data-testid="knowledge-search-submit"]').trigger("click");
    await flushPromises();

    expect(searchMock).toHaveBeenCalledWith("signed.jwt", {
      query: "PulseInk 渠道", types: [], authorities: [], updatedAfter: null, topK: 5
    });
    expect(wrapper.text()).toContain("RRF_HYBRID");
    expect(wrapper.text()).toContain("KNN_UNAVAILABLE");
    expect(wrapper.text()).toContain("产品规格");
    expect(wrapper.text()).toContain("PulseInk 支持多渠道");
  });

  it("never renders unexpected secret-like document fields", async () => {
    editorSession();
    listMock.mockResolvedValue({
      total: 1,
      items: [{
        documentId: 1, sourceId: "doc-1", originalFilename: "a.md",
        declaredMimeType: null, detectedMimeType: null, sizeBytes: 10,
        knowledgeType: "PRODUCT", authority: "OFFICIAL", documentVersion: 1,
        status: "ACTIVE", chunkCount: 0, failureCode: null,
        createdAt: "2026-08-04T12:00:00Z", updatedAt: "2026-08-04T12:00:00Z",
        indexName: "pulseink-secret-index",
        storagePath: "C:\\secret\\originals",
        embedding: [0.1, 0.2, 0.3],
        apiKey: "sk-should-not-render"
      } as never]
    });
    const wrapper = mountKnowledge();
    await flushPromises();

    expect(wrapper.text()).not.toContain("pulseink-secret-index");
    expect(wrapper.text()).not.toContain("C:\\secret");
    expect(wrapper.text()).not.toContain("sk-should-not-render");
  });

  it("keeps viewers read-only and shows complete loading/error/empty states", async () => {
    listMock.mockResolvedValue(emptyPage());
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 3, username: "viewer", role: "VIEWER" }
    });
    const wrapper = mountKnowledge();
    expect(wrapper.get('[data-testid="knowledge-loading"]').text()).toContain("加载");
    await flushPromises();
    expect(wrapper.get('[data-testid="knowledge-empty"]').text()).toContain("暂无");
    expect(wrapper.find('[data-testid="knowledge-upload-submit"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="knowledge-retry-document"]').exists()).toBe(false);

    listMock.mockRejectedValue(new Error("network down"));
    await wrapper.get('[data-testid="knowledge-reload"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="knowledge-error"]').text()).toContain("加载失败");
    expect(wrapper.find('[data-testid="knowledge-retry-document"]').exists()).toBe(false);
  });
});
