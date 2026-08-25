<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import * as echarts from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { getRunMetrics, type RunMetricResponse } from "../../api/feedback";
import { ApiError } from "../../api/http";
import { useAuthStore } from "../../stores/auth";
import { formatDate } from "../../utils/date";

echarts.use([LineChart, BarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = defineProps<{
  runId: number | null;
}>();

const auth = useAuthStore();
const router = useRouter();

const metrics = ref<RunMetricResponse[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const selectedPublication = ref<string>("all");
const chartElement = ref<HTMLDivElement | null>(null);
let chart: ReturnType<typeof echarts.init> | undefined;

const totals = computed(() => {
  let views = 0;
  let clicks = 0;
  let likes = 0;
  for (const metric of metrics.value) {
    views += metric.views;
    clicks += metric.clicks;
    likes += metric.likes;
  }
  return { views, clicks, likes };
});

const ctrPercent = computed(() =>
  totals.value.views > 0
    ? ((totals.value.clicks / totals.value.views) * 100).toFixed(2)
    : null
);

const publicationIds = computed(() =>
  [...new Set(metrics.value.map((metric) => metric.publicationId))].sort(
    (left, right) => left - right
  )
);

const filteredMetrics = computed(() => {
  if (selectedPublication.value === "all") {
    return metrics.value;
  }
  const publicationId = Number(selectedPublication.value);
  return metrics.value.filter((metric) => metric.publicationId === publicationId);
});

function renderChart() {
  if (!chartElement.value || filteredMetrics.value.length === 0) {
    return;
  }
  if (!chart) {
    chart = echarts.init(chartElement.value);
  }
  const dates = [...new Set(
    filteredMetrics.value.map((metric) => metric.metricDate).sort()
  )];
  const names = ["views", "clicks", "likes"];
  chart.setOption({
    color: ["#5c5ce6", "#8c53d7", "#24966b"],
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { left: 48, right: 20, top: 40, bottom: 36 },
    xAxis: { type: "category", data: dates },
    yAxis: { type: "value" },
    series: names.map((name, index) => ({
      name: `${name} · publication ${selectedPublication.value}`,
      type: "line",
      smooth: true,
      data: dates.map((date) => {
        const value = filteredMetrics.value
          .filter((metric) => metric.metricDate === date)
          .reduce(
            (sum, metric) => sum + [metric.views, metric.clicks, metric.likes][index],
            0
          );
        return [date, value] as [string, number];
      })
    }))
  });
}

function handleResize() {
  chart?.resize();
}

async function loadMetrics() {
  if (props.runId === null) {
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    metrics.value = await getRunMetrics(auth.accessToken!, props.runId);
    selectedPublication.value = "all";
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "加载失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({ path: "/login", query: { redirect: window.location.pathname } });
    }
  } finally {
    loading.value = false;
  }
  if (!errorMessage.value) {
    await nextTick();
    renderChart();
  }
}

watch(selectedPublication, () => {
  void nextTick(renderChart);
});

watch(() => props.runId, () => {
  chart?.dispose();
  chart = undefined;
  void loadMetrics();
}, { immediate: true });

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  chart?.dispose();
  chart = undefined;
});
window.addEventListener("resize", handleResize);
</script>

<template>
  <section class="metrics-panel">
    <div v-if="runId === null" class="panel-state" data-testid="metrics-no-run">
      请先启动 Run：在 Run Tab 启动并选择一个 Run，再来查看指标。
    </div>
    <template v-else>
      <div class="panel-toolbar">
        <h3>Kafka Feedback Metrics</h3>
        <button
          class="secondary-button"
          type="button"
          data-testid="metrics-reload"
          @click="loadMetrics"
        >
          刷新
        </button>
      </div>

      <div v-if="loading" class="panel-state" data-testid="metrics-loading">正在加载…</div>
      <div v-else-if="errorMessage" class="panel-state panel-error" data-testid="metrics-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadMetrics">重试</button>
      </div>
      <div v-else-if="metrics.length === 0" class="panel-state" data-testid="metrics-empty">
        暂无指标数据。页面不会为空数据伪造曲线。
      </div>
      <template v-else>
        <div class="metric-totals">
          <div>
            <dt>views</dt>
            <dd>{{ totals.views }}</dd>
          </div>
          <div>
            <dt>clicks</dt>
            <dd>{{ totals.clicks }}</dd>
          </div>
          <div>
            <dt>likes</dt>
            <dd>{{ totals.likes }}</dd>
          </div>
          <div>
            <dt>描述性 CTR（clicks / views）</dt>
            <dd>{{ ctrPercent === null ? "views=0，不计算" : `${ctrPercent}%` }}</dd>
          </div>
        </div>
        <p class="metric-note">
          以上仅为 Channel Sandbox 模拟反馈的描述性汇总，不宣称效果提升、显著性结论或因果关系。
        </p>

        <label class="publication-filter">
          按 publicationId 过滤
          <select v-model="selectedPublication" data-testid="metrics-publication-filter">
            <option value="all">全部 publication</option>
            <option v-for="id in publicationIds" :key="id" :value="String(id)">
              publication {{ id }}
            </option>
          </select>
        </label>

        <div
          ref="chartElement"
          class="metrics-chart"
          data-testid="metrics-chart"
        />
      </template>
    </template>
  </section>
</template>

<style scoped>
.metrics-panel {
  display: grid;
  gap: 1rem;
  padding: 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.panel-toolbar h3 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.panel-state {
  padding: 1.25rem;
  border-radius: 0.7rem;
  color: #596277;
  background: #f7f8fb;
  text-align: center;
  font-size: 0.84rem;
}

.panel-error {
  display: grid;
  justify-items: center;
  gap: 0.6rem;
  color: #a52e2e;
  background: #fff5f5;
}

.metric-totals {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 0.6rem;
}

.metric-totals div {
  padding: 0.8rem;
  border-radius: 0.65rem;
  background: #f7f8fb;
}

.metric-totals dt {
  color: #667085;
  font-size: 0.7rem;
  font-weight: 800;
}

.metric-totals dd {
  margin: 0.3rem 0 0;
  color: #172033;
  font-size: 1.2rem;
  font-weight: 800;
}

.metric-note {
  margin: 0;
  color: #667085;
  font-size: 0.76rem;
  line-height: 1.6;
}

.publication-filter {
  display: grid;
  gap: 0.35rem;
  max-width: 16rem;
  color: #596277;
  font-size: 0.8rem;
  font-weight: 700;
}

.publication-filter select {
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
}

.metrics-chart {
  width: 100%;
  min-height: 22rem;
}
</style>
