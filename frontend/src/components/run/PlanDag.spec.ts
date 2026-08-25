import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { ArtifactResponse, RunEventResponse } from "../../api/run";
import PlanDag from "./PlanDag.vue";

function planArtifact(tasks: unknown[]): ArtifactResponse {
  return {
    artifactId: "run-7-plan-v1",
    taskId: "planner",
    type: "PLAN",
    schemaVersion: "artifact-v1",
    artifactVersion: 1,
    status: "VALID",
    content: {
      plan: JSON.stringify({ schemaVersion: 1, tasks })
    },
    sourceRefs: [],
    createdAt: "2026-08-04T12:00:00Z"
  };
}

describe("PlanDag", () => {
  it("draws only the actual planned tasks with roles and dependencies", () => {
    const wrapper = mount(PlanDag, {
      props: {
        selectedMode: "ORCHESTRATED",
        artifacts: [
          planArtifact([
            {
              taskId: "research",
              role: "RESEARCHER",
              objective: "收集产品资料",
              dependsOn: [],
              outputArtifactType: "EVIDENCE_PACK",
              access: "READ"
            },
            {
              taskId: "create-blog",
              role: "CREATOR",
              objective: "撰写博客草稿",
              dependsOn: ["research"],
              outputArtifactType: "CONTENT_DRAFT",
              access: "READ"
            }
          ])
        ],
        events: []
      }
    });

    expect(wrapper.text()).toContain("RESEARCHER");
    expect(wrapper.text()).toContain("CREATOR");
    expect(wrapper.text()).toContain("收集产品资料");
    expect(wrapper.text()).toContain("撰写博客草稿");
    expect(wrapper.text()).toContain("research");
    expect(wrapper.get('[data-testid="plan-node-create-blog"]').text())
      .toContain("research");
  });

  it("marks only started tasks as running and never renders all five roles by default", () => {
    const events: RunEventResponse[] = [
      {
        sequence: 4,
        eventType: "TASK_STARTED",
        payload: { taskId: "research", role: "RESEARCHER" },
        createdAt: "2026-08-04T12:00:00Z"
      }
    ];
    const wrapper = mount(PlanDag, {
      props: {
        selectedMode: "ORCHESTRATED",
        artifacts: [
          planArtifact([
            {
              taskId: "research",
              role: "RESEARCHER",
              objective: "收集资料",
              dependsOn: [],
              outputArtifactType: "EVIDENCE_PACK",
              access: "READ"
            },
            {
              taskId: "review",
              role: "REVIEWER",
              objective: "审核草稿",
              dependsOn: [],
              outputArtifactType: "REVIEW_REPORT",
              access: "READ"
            }
          ])
        ],
        events
      }
    });

    expect(wrapper.get('[data-testid="plan-node-research"]').text()).toContain("执行中");
    expect(wrapper.get('[data-testid="plan-node-review"]').text()).toContain("待执行");
    expect(wrapper.text()).not.toContain("PLANNER");
    expect(wrapper.text()).not.toContain("STRATEGIST");
    expect(wrapper.text()).not.toContain("CREATOR");
  });

  it("does not fake a multi-agent DAG for REACT/DIRECT runs", () => {
    const wrapper = mount(PlanDag, {
      props: {
        selectedMode: "REACT",
        artifacts: [],
        events: []
      }
    });

    expect(wrapper.text()).toContain("不产生多 Agent Plan DAG");
    expect(wrapper.text()).not.toContain("RESEARCHER");
    expect(wrapper.text()).not.toContain("CREATOR");
    expect(wrapper.text()).not.toContain("REVIEWER");
  });

  it("falls back safely when plan content is not valid JSON", () => {
    const artifact: ArtifactResponse = {
      artifactId: "run-7-plan-v1",
      taskId: "planner",
      type: "PLAN",
      schemaVersion: "artifact-v1",
      artifactVersion: 1,
      status: "VALID",
      content: { plan: "{not-json" },
      sourceRefs: [],
      createdAt: "2026-08-04T12:00:00Z"
    };
    const wrapper = mount(PlanDag, {
      props: {
        selectedMode: "ORCHESTRATED",
        artifacts: [artifact],
        events: []
      }
    });

    expect(wrapper.text()).toContain("Plan 内容无法解析");
    expect(wrapper.text()).not.toContain("Unhandled");
  });
});
