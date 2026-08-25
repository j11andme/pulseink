import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { ArtifactResponse } from "../../api/run";
import ArtifactViewer from "./ArtifactViewer.vue";

describe("ArtifactViewer", () => {
  it("shows the latest VALID artifact by default and reveals invalidated history on demand", async () => {
    const artifacts: ArtifactResponse[] = [
      {
        artifactId: "run-7-draft-v1",
        taskId: "creator",
        type: "CONTENT_DRAFT",
        schemaVersion: "artifact-v1",
        artifactVersion: 1,
        status: "INVALIDATED",
        content: { title: "旧草稿" },
        sourceRefs: [],
        createdAt: "2026-08-04T12:00:00Z"
      },
      {
        artifactId: "run-7-draft-v2",
        taskId: "creator",
        type: "CONTENT_DRAFT",
        schemaVersion: "artifact-v1",
        artifactVersion: 2,
        status: "VALID",
        content: { title: "修复后的草稿" },
        sourceRefs: ["doc-1"],
        createdAt: "2026-08-04T12:05:00Z"
      }
    ];

    const wrapper = mount(ArtifactViewer, { props: { artifacts } });

    expect(wrapper.findAll('[data-testid="artifact-card"]')).toHaveLength(1);
    expect(wrapper.text()).toContain("修复后的草稿");
    expect(wrapper.text()).not.toContain("旧草稿");

    await wrapper.get('[data-testid="artifact-toggle-invalidated"]').trigger("click");

    expect(wrapper.findAll('[data-testid="artifact-card"]')).toHaveLength(2);
    expect(wrapper.text()).toContain("旧草稿");
    expect(wrapper.text()).toContain("INVALIDATED");
  });

  it("renders unknown artifact types with a safe JSON fallback", () => {
    const artifacts: ArtifactResponse[] = [
      {
        artifactId: "run-7-unknown",
        taskId: "custom",
        type: "FUTURE_ARTIFACT",
        schemaVersion: "artifact-v1",
        artifactVersion: 1,
        status: "VALID",
        content: { nested: { value: "<script>alert(1)</script>" } },
        sourceRefs: [],
        createdAt: "2026-08-04T12:00:00Z"
      }
    ];

    const wrapper = mount(ArtifactViewer, { props: { artifacts } });

    expect(wrapper.text()).toContain("FUTURE_ARTIFACT");
    expect(wrapper.get('[data-testid="artifact-json-fallback"]').text())
      .toContain("nested");
    expect(wrapper.html()).not.toContain("<script>");
  });

  it("shows an empty state without inventing artifacts", () => {
    const wrapper = mount(ArtifactViewer, { props: { artifacts: [] } });

    expect(wrapper.get('[data-testid="artifact-empty"]').text()).toContain("暂无 Artifact");
  });
});
