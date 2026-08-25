import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { RunEventResponse } from "../../api/run";
import ToolTrace from "./ToolTrace.vue";

function event(
  sequence: number,
  eventType: string,
  payload: Record<string, unknown>
): RunEventResponse {
  return {
    sequence,
    eventType,
    payload,
    createdAt: "2026-08-04T12:00:00Z"
  };
}

const events: RunEventResponse[] = [
  event(1, "DECISION_RECORDED", { decisionType: "TOOL_CALL", decisionSummary: "先检索知识库" }),
  event(2, "TOOL_CALL_STARTED", { qualifiedName: "builtin.knowledge_search", argumentNames: ["query"] }),
  event(3, "TOOL_CALL_COMPLETED", { qualifiedName: "builtin.knowledge_search", observation: "命中 3 条证据" }),
  event(4, "REVIEW_ISSUE_CREATED", { issueType: "MISSING_EVIDENCE", affectedTaskIds: ["creator"] }),
  event(5, "REPAIR_ROUND_STARTED", { repairRound: 1, path: "RESEARCHER_CREATOR", rootTaskIds: ["research"] }),
  event(6, "ARTIFACT_INVALIDATED", { artifactId: "run-7-draft-v1" }),
  event(7, "RUN_STATE_CHANGED", { fromState: "RUNNING", toState: "WAITING_APPROVAL" }),
  event(8, "RUNTIME_FAILED", { reasonCode: "MODEL_FAILURE" })
];

describe("ToolTrace", () => {
  it("shows decision summary, tool calls and observation summaries", () => {
    const wrapper = mount(ToolTrace, { props: { events } });

    expect(wrapper.text()).toContain("先检索知识库");
    expect(wrapper.text()).toContain("builtin.knowledge_search");
    expect(wrapper.text()).toContain("命中 3 条证据");
    expect(wrapper.text()).toContain("决策摘要");
  });

  it("filters events by trace category", async () => {
    const wrapper = mount(ToolTrace, { props: { events } });

    await wrapper.get('[data-testid="trace-filter"]').setValue("tool");
    expect(wrapper.text()).toContain("builtin.knowledge_search");
    expect(wrapper.text()).not.toContain("MISSING_EVIDENCE");
    expect(wrapper.findAll('[data-testid="trace-event"]')).toHaveLength(2);

    await wrapper.get('[data-testid="trace-filter"]').setValue("review");
    expect(wrapper.text()).toContain("MISSING_EVIDENCE");
    expect(wrapper.text()).not.toContain("builtin.knowledge_search");
    expect(wrapper.findAll('[data-testid="trace-event"]')).toHaveLength(1);

    await wrapper.get('[data-testid="trace-filter"]').setValue("repair");
    expect(wrapper.text()).toContain("RESEARCHER_CREATOR");
    expect(wrapper.text()).toContain("run-7-draft-v1");
    expect(wrapper.findAll('[data-testid="trace-event"]')).toHaveLength(2);
  });

  it("keeps state and error events visible with text labels", () => {
    const wrapper = mount(ToolTrace, { props: { events } });

    expect(wrapper.text()).toContain("RUNNING → WAITING_APPROVAL");
    expect(wrapper.text()).toContain("MODEL_FAILURE");
    expect(wrapper.text()).toContain("运行失败");
  });

  it("sorts events by sequence and shows an empty state", () => {
    const wrapper = mount(ToolTrace, {
      props: {
        events: [events[3], events[0]]
      }
    });

    const renderedSequences = wrapper
      .findAll('[data-testid="trace-event"]')
      .map((node) => Number(node.attributes("data-sequence")));
    expect(renderedSequences).toEqual([1, 4]);

    const empty = mount(ToolTrace, { props: { events: [] } });
    expect(empty.get('[data-testid="trace-empty"]').text()).toContain("暂无 Trace");
  });
});
