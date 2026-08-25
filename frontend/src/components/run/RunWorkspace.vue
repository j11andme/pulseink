<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type Ref
} from "vue";
import { useRouter } from "vue-router";
import type { CampaignResponse } from "../../api/campaign";
import { ApiError } from "../../api/http";
import {
  getRunTrace,
  listRuns,
  type ArtifactResponse,
  type RunEventResponse,
  type RunResponse,
  type RunTraceResponse
} from "../../api/run";
import {
  useRunEvents,
  type RunEventCursor,
  type RunEventEnvelope,
  type RunStreamStatus
} from "../../composables/useRunEvents";
import { useAuthStore } from "../../stores/auth";
import ArtifactViewer from "./ArtifactViewer.vue";
import ExecutionDecisionCard from "./ExecutionDecisionCard.vue";
import PlanDag from "./PlanDag.vue";
import ToolTrace from "./ToolTrace.vue";
import { formatDateTime } from "../../utils/date";

const props = defineProps<{
  campaign: CampaignResponse;
  selectedRunId?: number | null;
}>();

const emit = defineEmits<{
  "update:runId": [runId: number];
}>();

const auth = useAuthStore();
const router = useRouter();

const runs = ref<RunResponse[]>([]);
const runsLoading = ref(true);
const runsError = ref("");
const notice = ref("");

const activeRunId = ref<number | null>(null);
const trace = ref<RunTraceResponse | null>(null);
const traceLoading = ref(false);
const traceError = ref("");
let traceController: AbortController | undefined;

const cursor: Ref<RunEventCursor> = ref({ runId: 0 });
const stream = useRunEvents(cursor);
const streamStatus = computed(() => stream.status.value);
const streamError = computed(() => stream.errorMessage.value);

const selectedRun = computed(() =>
  runs.value.find((run) => run.runId === activeRunId.value) ?? null
);

const liveEvents = computed(() => stream.events.value as RunEventEnvelope[]);

const SETTLED_RUN_STATES = new Set([
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "WAITING_APPROVAL",
  "WAITING_HUMAN"
]);

const traceRunSettled = computed(() =>
  SETTLED_RUN_STATES.has(trace.value?.run.state ?? "")
);

const mergedEvents = computed<RunEventResponse[]>(() => {
  const bySequence = new Map<number, RunEventResponse>();
  for (const event of trace.value?.events ?? []) {
    bySequence.set(event.sequence, event);
  }
  for (const envelope of liveEvents.value) {
    bySequence.set(envelope.sequence, {
      sequence: envelope.sequence,
      eventType: envelope.eventType,
      payload: envelope.payload,
      createdAt: envelope.createdAt
    });
  }
  return [...bySequence.values()].sort(
    (left, right) => left.sequence - right.sequence
  );
});

const artifacts = computed<ArtifactResponse[]>(
  () => trace.value?.checkpoint?.artifacts ?? []
);

const activeRoles = computed(() => {
  const roles = new Map<string, { role: string; state: string }>();
  for (const event of mergedEvents.value) {
    if (event.eventType === "TASK_STARTED") {
      roles.set(String(event.payload.taskId ?? "task"), {
        role: String(event.payload.role ?? "UNKNOWN"),
        state: "RUNNING"
      });
    } else if (event.eventType === "TASK_COMPLETED") {
      roles.set(String(event.payload.taskId ?? "task"), {
        role: String(event.payload.role ?? "UNKNOWN"),
        state: "COMPLETED"
      });
    }
  }
  return [...roles.values()];
});

const connectionLabel = computed(() => {
  const run = trace.value?.run;
  if (run?.state === "FAILED") {
    return `Run 已失败${run.failureReason ? `：${run.failureReason}` : ""}`;
  }
  if (run?.state === "CANCELLED") {
    return "Run 已取消";
  }
  if (run?.state === "COMPLETED") {
    return "Run 已结束";
  }
  if (run?.state === "WAITING_APPROVAL") {
    return "等待人工审批";
  }
  if (run?.state === "WAITING_HUMAN") {
    return "等待人工处理";
  }
  const labels: Record<RunStreamStatus, string> = {
    idle: "未连接",
    connecting: "连接中",
    streaming: "实时接收中",
    reconnecting: "断线重连中",
    completed: "Run 已结束",
    waiting: "等待人工处理",
    failed: "连接失败"
  };
  return labels[streamStatus.value] ?? streamStatus.value;
});

async function loadRuns() {
  runsLoading.value = true;
  runsError.value = "";
  try {
    runs.value = await listRuns(auth.accessToken!, props.campaign.id);
    const requested = props.selectedRunId ?? null;
    const fallback = runs.value[0]?.runId ?? null;
    if (requested !== null && runs.value.some((run) => run.runId === requested)) {
      activeRunId.value = requested;
      emit("update:runId", requested);
    } else if (fallback !== null) {
      notice.value = requested === null
        ? ""
        : "未找到指定 Run，已回退到最新 Run。";
      activeRunId.value = fallback;
      emit("update:runId", fallback);
    }
    if (activeRunId.value !== null) {
      void loadTrace(activeRunId.value);
    }
  } catch (error) {
    runsError.value =
      error instanceof ApiError ? error.message : "加载 Run 历史失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: `/campaigns/${props.campaign.id}` }
      });
    }
  } finally {
    runsLoading.value = false;
  }
}

async function loadTrace(runId: number) {
  traceController?.abort();
  const controller = new AbortController();
  traceController = controller;
  traceLoading.value = true;
  traceError.value = "";
  trace.value = null;
  try {
    const snapshot = await getRunTrace(auth.accessToken!, runId, controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    trace.value = snapshot;
    runs.value = runs.value.map((run) =>
      run.runId === snapshot.run.runId ? snapshot.run : run
    );
    cursor.value = {
      runId,
      lastEventId: String(snapshot.lastEventSequence)
    };
    if (SETTLED_RUN_STATES.has(snapshot.run.state)) {
      stream.stop();
      return;
    }
    await stream.connect();
  } catch (error) {
    if (controller.signal.aborted) {
      return;
    }
    traceError.value =
      error instanceof ApiError ? error.message : "加载 Trace 快照失败，请稍后重试";
  } finally {
    if (traceController === controller) {
      traceLoading.value = false;
    }
  }
}

function selectRun(runId: number) {
  if (runId === activeRunId.value || runId <= 0) {
    return;
  }
  notice.value = "";
  stream.stop();
  activeRunId.value = runId;
  emit("update:runId", runId);
  void loadTrace(runId);
}

watch(
  () => props.selectedRunId,
  (nextRunId) => {
    if (nextRunId === null || nextRunId === undefined || nextRunId === activeRunId.value) {
      return;
    }
    if (runs.value.some((run) => run.runId === nextRunId)) {
      selectRun(nextRunId);
      return;
    }
    void loadRuns();
  }
);

watch(
  () => streamStatus.value,
  (next, previous) => {
    if (
      previous === "streaming" &&
      (next === "completed" || next === "waiting" || next === "failed") &&
      activeRunId.value !== null
    ) {
      void loadTrace(activeRunId.value);
    }
  }
);

onMounted(loadRuns);
onBeforeUnmount(() => {
  traceController?.abort();
  stream.stop();
});
</script>

<template>
  <section class="run-workspace">
    <div v-if="runsLoading" class="workspace-state" data-testid="run-workspace-loading">
      正在加载 Run 历史…
    </div>
    <div v-else-if="runsError" class="workspace-state workspace-error" data-testid="run-workspace-error">
      <p>{{ runsError }}</p>
      <button class="secondary-button" type="button" @click="loadRuns">重试</button>
    </div>
    <div v-else-if="runs.length === 0" class="workspace-state" data-testid="run-workspace-empty">
      暂无 Run，请先在上方启动一个 Run。
    </div>
    <template v-else>
      <p v-if="notice" class="workspace-notice" role="status">{{ notice }}</p>

      <div class="run-history">
        <span>Run 历史</span>
        <button
          v-for="run in runs"
          :key="run.runId"
          class="history-button"
          :class="{ 'history-active': run.runId === activeRunId }"
          type="button"
          :data-testid="`run-select-${run.runId}`"
          @click="selectRun(run.runId)"
        >
          #{{ run.runId }} · {{ run.selectedMode ?? "未选择" }} · {{ run.state }}
        </button>
      </div>

      <div class="connection-row">
        <span class="connection-label">SSE：{{ connectionLabel }}</span>
        <span v-if="streamError && !traceRunSettled" class="connection-error">
          {{ streamError }}
        </span>
        <button
          v-if="streamStatus === 'failed' && !traceRunSettled"
          class="secondary-button"
          type="button"
          data-testid="run-stream-reconnect"
          @click="stream.reconnect"
        >
          重新连接
        </button>
      </div>

      <div v-if="traceLoading && !trace" class="workspace-state">
        正在加载 Trace…
      </div>
      <div v-else-if="traceError" class="workspace-state workspace-error">
        <p>{{ traceError }}</p>
        <button
          class="secondary-button"
          type="button"
          @click="activeRunId && loadTrace(activeRunId)"
        >
          重试
        </button>
      </div>
      <div v-else-if="!trace" class="workspace-state">Run 已选择，等待 Trace 快照。</div>
      <div v-else class="run-workspace-grid">
        <aside class="run-column">
          <div v-if="selectedRun">
            <p class="column-title">执行决策</p>
            <ExecutionDecisionCard :decision="selectedRun" />
          </div>

          <div class="role-panel">
            <p class="column-title">实际 Agent / Task</p>
            <div v-if="activeRoles.length === 0" class="role-empty">
              <template v-if="selectedRun?.selectedMode === 'ORCHESTRATED'">
                尚无已启动的 Task（等待 Plan 调度）。
              </template>
              <template v-else-if="selectedRun?.selectedMode === 'REACT'">
                REACT 由 UnifiedCampaignAgent 执行，不拆分为多 Agent。
              </template>
              <template v-else-if="selectedRun?.selectedMode === 'DIRECT'">
                DIRECT 由 DirectAgentEngine 单次执行，不拆分为多 Agent。
              </template>
              <template v-else>暂无实际 Agent。</template>
            </div>
            <ul v-else class="role-list">
              <li v-for="(task, index) in activeRoles" :key="index">
                <strong>{{ task.role }}</strong>
                <span>{{ task.state === "RUNNING" ? "执行中" : "已完成" }}</span>
              </li>
            </ul>
          </div>

          <PlanDag
            :selected-mode="selectedRun?.selectedMode ?? null"
            :artifacts="artifacts"
            :events="mergedEvents"
          />
        </aside>

        <section class="run-column">
          <p class="column-title">Artifact / Evidence / Draft</p>
          <ArtifactViewer :artifacts="artifacts" />
        </section>

        <aside class="run-column">
          <p class="column-title">工具 / Review / Repair</p>
          <ToolTrace :events="mergedEvents" />
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.run-workspace {
  display: grid;
  gap: 1rem;
}

.workspace-state {
  padding: 1.5rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  color: #596277;
  background: #fff;
  text-align: center;
}

.workspace-error {
  display: grid;
  justify-items: center;
  gap: 0.7rem;
  color: #a52e2e;
  background: #fff5f5;
}

.workspace-notice {
  margin: 0;
  padding: 0.7rem 0.85rem;
  border-radius: 0.65rem;
  color: #7a3c12;
  background: #fdf1e5;
  font-size: 0.84rem;
}

.run-history {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.75rem;
  background: #fff;
  color: #596277;
  font-size: 0.82rem;
}

.history-button {
  padding: 0.45rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.55rem;
  color: #3d465a;
  background: #fff;
  font-size: 0.78rem;
}

.history-active {
  border-color: #5c5ce6;
  color: #5c5ce6;
  background: #f0f0ff;
  font-weight: 800;
}

.connection-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.7rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.7rem;
  background: #fff;
  color: #3d465a;
  font-size: 0.8rem;
}

.connection-label {
  font-weight: 700;
}

.connection-error {
  color: #a52e2e;
}

.run-workspace-grid {
  display: grid;
  grid-template-columns: 300px minmax(420px, 1fr) 360px;
  gap: 1rem;
  align-items: start;
}

.run-column {
  display: grid;
  gap: 0.8rem;
  min-width: 0;
}

.column-title {
  margin: 0;
  color: #667085;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.role-panel {
  padding: 1rem 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.role-empty {
  padding: 0.8rem;
  border-radius: 0.6rem;
  color: #596277;
  background: #f7f8fb;
  font-size: 0.8rem;
  line-height: 1.6;
}

.role-list {
  display: grid;
  gap: 0.4rem;
  margin: 0.6rem 0 0;
  padding: 0;
  list-style: none;
}

.role-list li {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem 0.65rem;
  border-radius: 0.55rem;
  color: #172033;
  background: #f7f8fb;
  font-size: 0.8rem;
}

@media (max-width: 1180px) {
  .run-workspace-grid {
    grid-template-columns: 1fr;
  }
}
</style>
