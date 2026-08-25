<script setup lang="ts">
import { computed } from "vue";
import type { ArtifactResponse, ExecutionMode, RunEventResponse } from "../../api/run";

const props = defineProps<{
  selectedMode: ExecutionMode | null;
  artifacts: ArtifactResponse[];
  events: RunEventResponse[];
}>();

interface PlanTaskView {
  taskId: string;
  role: string;
  objective: string;
  dependsOn: string[];
  outputArtifactType: string;
  access: string;
}

interface PlanView {
  schemaVersion: number;
  tasks: PlanTaskView[];
}

const planArtifact = computed(() =>
  props.artifacts.find(
    (artifact) => artifact.type === "PLAN" && artifact.status === "VALID"
  )
);

const plan = computed<PlanView | null>(() => {
  if (!planArtifact.value) {
    return null;
  }
  try {
    const raw = String(planArtifact.value.content?.plan ?? "{}");
    return JSON.parse(raw) as PlanView;
  } catch {
    return null;
  }
});

const parseFailed = computed(
  () => planArtifact.value !== undefined && plan.value === null
);

const taskState = computed(() => {
  const states = new Map<string, "PENDING" | "RUNNING" | "COMPLETED">();
  for (const event of [...props.events].sort((a, b) => a.sequence - b.sequence)) {
    if (event.eventType === "TASK_STARTED") {
      const taskId = String(event.payload.taskId ?? "");
      if (taskId) {
        states.set(taskId, "RUNNING");
      }
    } else if (event.eventType === "TASK_COMPLETED") {
      const taskId = String(event.payload.taskId ?? "");
      if (taskId) {
        states.set(taskId, "COMPLETED");
      }
    }
  }
  return states;
});
</script>

<template>
  <section class="plan-dag">
    <h3>Plan DAG</h3>
    <div v-if="parseFailed" class="plan-fallback">
      Plan 内容无法解析，请以 Artifact 中的原始 JSON 为准。
    </div>
    <div v-else-if="!plan && selectedMode !== 'ORCHESTRATED'" class="plan-fallback">
      {{ selectedMode ?? "该" }} 模式不产生多 Agent Plan DAG。
    </div>
    <div v-else-if="!plan" class="plan-fallback">
      暂无 Plan（尚未规划或仍在执行）。
    </div>
    <ol v-else class="plan-nodes">
      <li
        v-for="task in plan.tasks"
        :key="task.taskId"
        class="plan-node"
        :data-testid="`plan-node-${task.taskId}`"
        :data-state="taskState.get(task.taskId) ?? 'PENDING'"
      >
        <div class="plan-node-head">
          <strong>{{ task.role }}</strong>
          <span>{{ taskState.get(task.taskId) === 'RUNNING' ? '执行中'
            : taskState.get(task.taskId) === 'COMPLETED' ? '已完成' : '待执行' }}</span>
        </div>
        <p>{{ task.objective }}</p>
        <div class="plan-node-meta">
          <span>taskId：{{ task.taskId }}</span>
          <span>输出：{{ task.outputArtifactType }}</span>
          <span v-if="task.dependsOn.length > 0">
            依赖：{{ task.dependsOn.join(", ") }}
          </span>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.plan-dag {
  padding: 1rem 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.plan-dag h3 {
  margin: 0 0 0.75rem;
  color: #172033;
  font-size: 1rem;
}

.plan-fallback {
  padding: 1rem;
  border-radius: 0.65rem;
  color: #596277;
  background: #f7f8fb;
  font-size: 0.82rem;
}

.plan-nodes {
  display: grid;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.plan-node {
  padding: 0.7rem 0.8rem;
  border-left: 3px solid #c9cdf4;
  border-radius: 0.6rem;
  background: #fbfbfd;
}

.plan-node[data-state="RUNNING"] {
  border-left-color: #5c5ce6;
  background: #f4f4ff;
}

.plan-node[data-state="COMPLETED"] {
  border-left-color: #24966b;
}

.plan-node-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
}

.plan-node-head strong {
  color: #4a3f9c;
  font-size: 0.82rem;
}

.plan-node-head span {
  color: #667085;
  font-size: 0.72rem;
}

.plan-node p {
  margin: 0.4rem 0 0;
  color: #172033;
  font-size: 0.84rem;
  line-height: 1.6;
}

.plan-node-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.45rem;
  color: #667085;
  font-size: 0.7rem;
}
</style>
