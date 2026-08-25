import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { IntegrationItem } from "../../api/integration";
import IntegrationCard from "./IntegrationCard.vue";

describe("IntegrationCard", () => {
  it("shows configured status with text, summary and capability tags", () => {
    const wrapper = mount(IntegrationCard, {
      props: {
        integration: {
          id: "model",
          displayName: "模型 Provider",
          category: "MODEL",
          status: "CONFIGURED",
          summary: "本地演示 Provider：fake",
          capabilities: ["统一模型端口", "故障回退"]
        }
      }
    });

    expect(wrapper.text()).toContain("模型 Provider");
    expect(wrapper.text()).toContain("MODEL");
    expect(wrapper.text()).toContain("已配置");
    expect(wrapper.text()).toContain("本地演示 Provider：fake");
    expect(wrapper.text()).toContain("统一模型端口");
    expect(wrapper.text()).toContain("故障回退");
  });

  it("shows disabled status with text so state is not color-only", () => {
    const wrapper = mount(IntegrationCard, {
      props: {
        integration: {
          id: "kafka-feedback",
          displayName: "Kafka Feedback",
          category: "MESSAGING",
          status: "DISABLED",
          summary: "消费者已禁用",
          capabilities: ["Inbox 去重"]
        }
      }
    });

    expect(wrapper.text()).toContain("已禁用");
    expect(wrapper.get('[data-testid="integration-status"]').text()).toBe("已禁用");
  });

  it("renders only the known public fields when unexpected fields arrive", () => {
    const wrapper = mount(IntegrationCard, {
      props: {
        integration: {
          id: "model",
          displayName: "模型 Provider",
          category: "MODEL",
          status: "CONFIGURED",
          summary: "fake",
          capabilities: [],
          apiKey: "sk-secret",
          baseUrl: "http://internal/api/v3",
          password: "hunter2"
        } as IntegrationItem
      }
    });

    expect(wrapper.text()).not.toContain("sk-secret");
    expect(wrapper.text()).not.toContain("http://internal");
    expect(wrapper.text()).not.toContain("hunter2");
  });
});
