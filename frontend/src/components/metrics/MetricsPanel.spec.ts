import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getRunMetrics, type RunMetricResponse } from "../../api/feedback";
import { useAuthStore } from "../../stores/auth";
import MetricsPanel from "./MetricsPanel.vue";

vi.mock("../../api/feedback", () => ({
  getRunMetrics: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const echartsMocks = vi.hoisted(() => {
  const setOption = vi.fn();
  const resize = vi.fn();
  const dispose = vi.fn();
  const init = vi.fn(() => ({ setOption, resize, dispose }));
  const use = vi.fn();
  return { setOption, resize, dispose, init, use };
});

vi.mock("echarts/core", () => ({
  init: echartsMocks.init,
  use: echartsMocks.use
}));
vi.mock("echarts/charts", () => ({ LineChart: {}, BarChart: {} }));
vi.mock("echarts/components", () => ({
  GridComponent: {}, TooltipComponent: {}, LegendComponent: {}
}));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

const { setOption, resize, dispose, init } = echartsMocks;

const getRunMetricsMock = vi.mocked(getRunMetrics);

const metrics: RunMetricResponse[] = [
  { publicationId: 1, metricDate: "2026-08-04", views: 100, clicks: 12, likes: 5 },
  { publicationId: 2, metricDate: "2026-08-04", views: 50, clicks: 5, likes: 2 },
  { publicationId: 1, metricDate: "2026-08-05", views: 200, clicks: 30, likes: 9 }
];

function mountPanel(runId: number | null = 12) {
  return mount(MetricsPanel, {
    props: { runId }
  });
}

describe("MetricsPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getRunMetricsMock.mockReset();
    setOption.mockReset();
    resize.mockReset();
    dispose.mockReset();
    init.mockClear().mockReturnValue({ setOption, resize, dispose });
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("aggregates real views/clicks/likes and shows a descriptive CTR without division by zero", async () => {
    getRunMetricsMock.mockResolvedValue(metrics);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.text()).toContain("350");
    expect(wrapper.text()).toContain("47");
    expect(wrapper.text()).toContain("16");
    expect(wrapper.text()).toContain("13.43%");
    expect(wrapper.text()).toContain("clicks / views");
    expect(wrapper.text()).not.toContain("NaN");
    expect(wrapper.text()).not.toContain("uplift");
  });

  it("draws only the selected publication series and refreshes ECharts", async () => {
    getRunMetricsMock.mockResolvedValue(metrics);
    const wrapper = mountPanel();
    await flushPromises();
    await nextTick();

    expect(init).toHaveBeenCalled();
    expect(setOption).toHaveBeenCalled();
    const aggregate = setOption.mock.calls.at(-1)?.[0] as {
      series: Array<{ name: string; data: Array<[string, number]> }>;
    };
    expect(aggregate.series[0].data).toEqual([
      ["2026-08-04", 150],
      ["2026-08-05", 200]
    ]);

    await wrapper.get('[data-testid="metrics-publication-filter"]').setValue("1");
    await nextTick();
    const firstOption = setOption.mock.calls.at(-1)?.[0] as {
      series: Array<{ name: string; data: Array<[string, number]> }>;
    };
    expect(firstOption.series[0].name).toContain("publication 1");
    expect(firstOption.series[0].data).toHaveLength(2);

    await wrapper.get('[data-testid="metrics-publication-filter"]').setValue("2");
    await nextTick();
    const filtered = setOption.mock.calls.at(-1)?.[0] as {
      series: Array<{ name: string; data: Array<[string, number]> }>;
    };
    expect(filtered.series[0].name).toContain("publication 2");
    expect(filtered.series[0].data).toHaveLength(1);
  });

  it("does not render a fake curve for empty data", async () => {
    getRunMetricsMock.mockResolvedValue([]);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.get('[data-testid="metrics-empty"]').text()).toContain("暂无指标");
    expect(init).not.toHaveBeenCalled();
    expect(setOption).not.toHaveBeenCalled();
  });

  it("resizes with the window and disposes the chart on unmount", async () => {
    getRunMetricsMock.mockResolvedValue(metrics);
    const wrapper = mountPanel();
    await flushPromises();
    await nextTick();

    window.dispatchEvent(new Event("resize"));
    expect(resize).toHaveBeenCalled();

    wrapper.unmount();
    expect(dispose).toHaveBeenCalled();
  });

  it("prompts to select a run before loading", () => {
    const wrapper = mountPanel(null);
    expect(wrapper.get('[data-testid="metrics-no-run"]').text()).toContain("请先启动 Run");
    expect(getRunMetricsMock).not.toHaveBeenCalled();
  });
});
