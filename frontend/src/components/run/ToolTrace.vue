<script setup lang="ts">
import { computed, ref } from "vue";
import type { RunEventResponse } from "../../api/run";
import { formatDateTime } from "../../utils/date";

const props = defineProps<{
  events: RunEventResponse[];
}>();

type TraceFilter = "all" | "decision" | "tool" | "review" | "repair" | "state" | "error";

const filter = ref<TraceFilter>("all");

const sortedEvents = computed(() =>
  [...props.events].sort((left, right) => left.sequence - right.sequence)
);

function categoryOf(event: RunEventResponse): Exclude<TraceFilter, "all"> {
  switch (event.eventType) {
    case "DECISION_RECORDED":
      return "decision";
    case "TOOL_CALL_STARTED":
    case "TOOL_CALL_COMPLETED":
      return "tool";
    case "REVIEW_ISSUE_CREATED":
      return "review";
    case "REPAIR_ROUND_STARTED":
    case "ARTIFACT_INVALIDATED":
    case "REPAIR_EXHAUSTED":
      return "repair";
    case "RUNTIME_FAILED":
      return "error";
    default:
      return "state";
  }
}

const visibleEvents = computed(() =>
  filter.value === "all"
    ? sortedEvents.value
    : sortedEvents.value.filter((event) => categoryOf(event) === filter.value)
);

const eventLabels: Record<string, string> = {
  EXECUTION_MODE_SELECTED: "执行模式已选择",
  RUN_STATE_CHANGED: "Run 状态变化",
  MODEL_ROUTE_SELECTED: "模型路由选择",
  DECISION_RECORDED: "决策摘要",
  TOOL_CALL_STARTED: "工具调用开始",
  TOOL_CALL_COMPLETED: "工具调用完成",
  ARTIFACT_CREATED: "Artifact 创建",
  APPROVAL_REQUIRED: "等待人工审批",
  RUNTIME_FAILED: "运行失败",
  PLAN_VALIDATED: "Plan 验证通过",
  TASK_STARTED: "任务开始",
  TASK_COMPLETED: "任务完成",
  REVIEW_ISSUE_CREATED: "Review 问题",
  REPAIR_ROUND_STARTED: "修复轮次开始",
  ARTIFACT_INVALIDATED: "Artifact 失效",
  REPAIR_EXHAUSTED: "修复轮次耗尽"
};

function renderPayload(event: RunEventResponse): string {
  const payload = event.payload ?? {};
  switch (event.eventType) {
    case "RUN_STATE_CHANGED":
      return `${String(payload.fromState ?? "")} → ${String(payload.toState ?? "")}`;
    case "TOOL_CALL_STARTED":
      return `${String(payload.qualifiedName ?? "")} · 参数: ${String(
        payload.argumentNames ?? ""
      )}`;
    case "TOOL_CALL_COMPLETED":
      return `${String(payload.qualifiedName ?? "")} · 观察摘要: ${String(
        payload.observation ?? ""
      )}`;
    case "DECISION_RECORDED":
      return `${String(payload.decisionType ?? "")} · ${String(
        payload.decisionSummary ?? ""
      )}`;
    case "REVIEW_ISSUE_CREATED":
      return `${String(payload.issueType ?? "")} · affected: ${String(
        payload.affectedTaskIds ?? ""
      )} · round ${String(payload.repairRound ?? 0)}`;
    case "REPAIR_ROUND_STARTED":
      return `${String(payload.path ?? "")} · root: ${String(
        payload.rootTaskIds ?? ""
      )} · round ${String(payload.repairRound ?? 0)}`;
    case "RUNTIME_FAILED":
      return String(payload.reasonCode ?? "");
    default:
      return Object.entries(payload)
        .map(([key, value]) => `${key}=${String(value)}`)
        .join(" · ");
  }
}
</script>

<template>
  <section class="tool-trace">
    <div class="trace-toolbar">
      <h3>Run Trace</h3>
      <select v-model="filter" data-testid="trace-filter">
        <option value="all">全部</option>
        <option value="decision">决策摘要</option>
        <option value="tool">工具调用</option>
        <option value="review">Review</option>
        <option value="repair">Repair / 失效</option>
        <option value="state">状态</option>
        <option value="error">错误</option>
      </select>
    </div>

    <div v-if="visibleEvents.length === 0" class="trace-empty" data-testid="trace-empty">
      暂无 Trace 事件。
    </div>
    <ul v-else class="trace-list">
      <li
        v-for="event in visibleEvents"
        :key="event.sequence"
        class="trace-event"
        data-testid="trace-event"
        :data-sequence="event.sequence"
        :data-category="categoryOf(event)"
      >
        <div class="trace-event-head">
          <strong>{{ eventLabels[event.eventType] ?? event.eventType }}</strong>
          <span>#{{ event.sequence }} · {{ formatDateTime(event.createdAt) }}</span>
        </div>
        <p>{{ renderPayload(event) }}</p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.tool-trace {
  padding: 1rem 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.trace-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.trace-toolbar h3 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.trace-toolbar select {
  padding: 0.45rem 0.6rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
}

.trace-empty {
  padding: 1.25rem;
  border-radius: 0.65rem;
  color: #596277;
  background: #f7f8fb;
  text-align: center;
  font-size: 0.84rem;
}

.trace-list {
  display: grid;
  gap: 0.45rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-event {
  padding: 0.6rem 0.75rem;
  border-radius: 0.6rem;
  background: #fbfbfd;
}

.trace-event[data-category="error"] {
  background: #fff5f5;
}

.trace-event[data-category="review"],
.trace-event[data-category="repair"] {
  background: #fffaf1;
}

.trace-event-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.6rem;
}

.trace-event-head strong {
  color: #172033;
  font-size: 0.8rem;
}

.trace-event-head span {
  color: #667085;
  font-size: 0.68rem;
}

.trace-event p {
  margin: 0.3rem 0 0;
  color: #3d465a;
  font-size: 0.78rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
</style>
