import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import ExecutionDecisionCard from "./ExecutionDecisionCard.vue";

describe("ExecutionDecisionCard", () => {
  it("shows why AUTO selected ORCHESTRATED with readable Chinese reasons", () => {
    const wrapper = mount(ExecutionDecisionCard, {
      props: {
        decision: {
          requestedPolicy: "ADAPTIVE",
          selectedMode: "ORCHESTRATED",
          reasonCodes: ["DECOMPOSABLE_OR_HIGH_RISK"],
          selectorPolicyVersion: "selector-v1"
        }
      }
    });

    expect(wrapper.text()).toContain("AUTO（推荐）");
    expect(wrapper.text()).toContain("ORCHESTRATED");
    expect(wrapper.text()).toContain("可并行或高事实风险");
    expect(wrapper.text()).toContain("selector-v1");
    expect(wrapper.get('[data-testid="decision-reason-code"]').text())
      .toContain("DECOMPOSABLE_OR_HIGH_RISK");
  });

  it("maps manual and unified-context reasons and keeps the raw code visible", () => {
    const wrapper = mount(ExecutionDecisionCard, {
      props: {
        decision: {
          requestedPolicy: "ADAPTIVE",
          selectedMode: "REACT",
          reasonCodes: ["UNIFIED_CONTEXT_PREFERRED"],
          selectorPolicyVersion: "selector-v1"
        }
      }
    });

    expect(wrapper.text()).toContain("优先统一上下文");
    expect(wrapper.text()).toContain("UNIFIED_CONTEXT_PREFERRED");
  });

  it("uses a generic fallback for unknown reason codes without crashing", () => {
    const wrapper = mount(ExecutionDecisionCard, {
      props: {
        decision: {
          requestedPolicy: "ADAPTIVE",
          selectedMode: "REACT",
          reasonCodes: ["FUTURE_REASON_CODE"],
          selectorPolicyVersion: "selector-v1"
        }
      }
    });

    expect(wrapper.text()).toContain("未知原因");
    expect(wrapper.text()).toContain("FUTURE_REASON_CODE");
  });

  it("renders budget and feature snapshot when present", () => {
    const wrapper = mount(ExecutionDecisionCard, {
      props: {
        decision: {
          requestedPolicy: "DIRECT",
          selectedMode: "DIRECT",
          reasonCodes: ["MANUAL_POLICY_OVERRIDE"],
          selectorPolicyVersion: "selector-v1",
          featureSnapshot: { channelCount: 2, factualRisk: 0.1 },
          estimatedTokenBudget: 8000
        }
      }
    });

    expect(wrapper.text()).toContain("手动指定策略");
    expect(wrapper.text()).toContain("8000");
    expect(wrapper.text()).toContain("channelCount");
  });

  it("updates the feature snapshot when the selected run changes", async () => {
    const wrapper = mount(ExecutionDecisionCard, {
      props: {
        decision: {
          requestedPolicy: "DIRECT",
          selectedMode: "DIRECT",
          reasonCodes: [],
          selectorPolicyVersion: "selector-v1",
          featureSnapshot: { channelCount: 1 }
        }
      }
    });

    await wrapper.setProps({
      decision: {
        requestedPolicy: "ORCHESTRATED",
        selectedMode: "ORCHESTRATED",
        reasonCodes: [],
        selectorPolicyVersion: "selector-v1",
        featureSnapshot: { factualRisk: 0.9 }
      }
    });

    expect(wrapper.text()).toContain("factualRisk");
    expect(wrapper.text()).not.toContain("channelCount");
  });
});
