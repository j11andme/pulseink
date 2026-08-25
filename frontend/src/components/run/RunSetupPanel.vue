<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import type { CampaignResponse } from "../../api/campaign";
import { ApiError } from "../../api/http";
import {
  startRun,
  type ExecutionPolicy,
  type RunResponse,
  type TaskProperties
} from "../../api/run";
import { useAuthStore } from "../../stores/auth";
import ExecutionDecisionCard from "./ExecutionDecisionCard.vue";

const props = defineProps<{
  campaign: CampaignResponse;
}>();

const emit = defineEmits<{
  "run-created": [run: RunResponse];
}>();

const auth = useAuthStore();
const canStart = computed(
  () => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN"
);

const requestedPolicy = ref<ExecutionPolicy>("ADAPTIVE");
const task = reactive<TaskProperties>({
  decomposability: 0.5,
  channelCount: props.campaign.channels.length,
  sourceDiversity: 1,
  parallelResearchBranches: 1,
  sequentialDependency: 0.7,
  factualRisk: 0.4,
  toolBreadth: 2,
  latencyBudgetMs: 20_000
});

const showAdvanced = ref(false);
const isSubmitting = ref(false);
const errorMessage = ref("");
const createdRun = ref<RunResponse | null>(null);

const templates = {
  quick: {
    label: "快速草稿",
    policy: "DIRECT" as ExecutionPolicy,
    properties: {
      decomposability: 0.1,
      sourceDiversity: 0,
      parallelResearchBranches: 0,
      sequentialDependency: 0.1,
      factualRisk: 0.1,
      toolBreadth: 0,
      latencyBudgetMs: 8000
    }
  },
  research: {
    label: "检索创作",
    policy: "ADAPTIVE" as ExecutionPolicy,
    properties: {
      decomposability: 0.5,
      sourceDiversity: 2,
      parallelResearchBranches: 1,
      sequentialDependency: 0.8,
      factualRisk: 0.4,
      toolBreadth: 2,
      latencyBudgetMs: 20_000
    }
  },
  multichannel: {
    label: "多渠道活动",
    policy: "ADAPTIVE" as ExecutionPolicy,
    properties: {
      decomposability: 0.8,
      sourceDiversity: 3,
      parallelResearchBranches: 3,
      sequentialDependency: 0.4,
      factualRisk: 0.8,
      toolBreadth: 3,
      latencyBudgetMs: 20_000
    }
  }
};

function applyTemplate(name: string) {
  const template = templates[name as keyof typeof templates];
  if (!template) {
    return;
  }
  requestedPolicy.value = template.policy;
  Object.assign(task, template.properties, {
    channelCount: props.campaign.channels.length
  });
  createdRun.value = null;
  errorMessage.value = "";
}

function probabilityValid(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= 1;
}

function validate(): string {
  if (!probabilityValid(task.decomposability)) {
    return "decomposability 必须在 0—1 之间";
  }
  if (!probabilityValid(task.sequentialDependency)) {
    return "sequentialDependency 必须在 0—1 之间";
  }
  if (!probabilityValid(task.factualRisk)) {
    return "factualRisk 必须在 0—1 之间";
  }
  for (const [name, value] of [
    ["sourceDiversity", task.sourceDiversity],
    ["parallelResearchBranches", task.parallelResearchBranches],
    ["toolBreadth", task.toolBreadth]
  ] as const) {
    if (!Number.isFinite(value) || value < 0) {
      return `${name} 不能为负数`;
    }
  }
  if (!Number.isFinite(task.latencyBudgetMs) || task.latencyBudgetMs <= 0) {
    return "latencyBudgetMs 必须为正数";
  }
  return "";
}

async function submit() {
  if (!canStart.value || isSubmitting.value) {
    return;
  }
  const validationError = validate();
  if (validationError) {
    errorMessage.value = validationError;
    return;
  }
  isSubmitting.value = true;
  errorMessage.value = "";
  createdRun.value = null;
  try {
    const run = await startRun(auth.accessToken!, props.campaign.id, {
      requestedPolicy: requestedPolicy.value,
      taskProperties: {
        ...task,
        channelCount: props.campaign.channels.length
      }
    });
    createdRun.value = run;
    emit("run-created", run);
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "启动 Run 失败，请稍后重试";
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <section class="run-setup">
    <h3>启动 Run</h3>
    <div v-if="!canStart" class="viewer-hint">Viewer 只读，不能启动 Run。</div>
    <template v-else>
      <div class="template-row">
        <button
          v-for="(template, key) in templates"
          :key="key"
          class="secondary-button"
          type="button"
          :data-testid="`run-template-${key}`"
          @click="applyTemplate(key)"
        >
          {{ template.label }}
        </button>
      </div>

      <div class="setup-grid">
        <label>
          执行策略
          <select v-model="requestedPolicy" data-testid="run-policy-select">
            <option value="ADAPTIVE">AUTO（推荐）</option>
            <option value="DIRECT">DIRECT</option>
            <option value="REACT">REACT</option>
            <option value="ORCHESTRATED">ORCHESTRATED</option>
          </select>
        </label>
        <label>
          渠道数（来自 Campaign，不可伪造）
          <input
            :value="task.channelCount"
            type="number"
            data-testid="task-channel-count"
            disabled
          />
        </label>
        <label>
          latencyBudgetMs
          <input v-model.number="task.latencyBudgetMs" type="number" data-testid="task-latency" />
        </label>
      </div>

      <button
        class="text-button"
        type="button"
        data-testid="run-advanced-toggle"
        @click="showAdvanced = !showAdvanced"
      >
        {{ showAdvanced ? "收起高级设置" : "高级设置" }}
      </button>

      <div v-if="showAdvanced" class="setup-grid advanced-grid">
        <label>
          decomposability
          <input v-model.number="task.decomposability" type="number" step="0.1" data-testid="task-decomposability" />
        </label>
        <label>
          sourceDiversity
          <input v-model.number="task.sourceDiversity" type="number" data-testid="task-source-diversity" />
        </label>
        <label>
          parallelResearchBranches
          <input v-model.number="task.parallelResearchBranches" type="number" data-testid="task-parallel-branches" />
        </label>
        <label>
          sequentialDependency
          <input v-model.number="task.sequentialDependency" type="number" step="0.1" data-testid="task-sequential-dependency" />
        </label>
        <label>
          factualRisk
          <input v-model.number="task.factualRisk" type="number" step="0.1" data-testid="task-factual-risk" />
        </label>
        <label>
          toolBreadth
          <input v-model.number="task.toolBreadth" type="number" data-testid="task-tool-breadth" />
        </label>
      </div>

      <div class="submit-row">
        <button
          class="primary-button"
          type="button"
          data-testid="run-submit"
          :disabled="isSubmitting"
          @click="submit"
        >
          {{ isSubmitting ? "正在启动…" : "启动 Run" }}
        </button>
      </div>

      <p v-if="errorMessage" class="form-error" role="alert" data-testid="run-error">
        {{ errorMessage }}
      </p>
    </template>

    <ExecutionDecisionCard v-if="createdRun" :decision="createdRun" class="created-decision" />
  </section>
</template>

<style scoped>
.run-setup {
  display: grid;
  gap: 0.9rem;
  padding: 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.run-setup h3 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.viewer-hint {
  padding: 0.8rem;
  border-radius: 0.6rem;
  color: #7a3c12;
  background: #fdf1e5;
  font-size: 0.82rem;
}

.template-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.setup-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.7rem;
}

.setup-grid label {
  display: grid;
  gap: 0.3rem;
  color: #596277;
  font-size: 0.78rem;
  font-weight: 700;
}

.setup-grid input,
.setup-grid select {
  width: 100%;
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  outline: none;
  color: #172033;
  background: #fff;
}

.setup-grid input:disabled {
  color: #596277;
  background: #f0f2f7;
}

.text-button {
  justify-self: start;
  padding: 0.3rem 0.5rem;
  border: 0;
  color: #5c5ce6;
  background: transparent;
  font-size: 0.8rem;
  font-weight: 700;
}

.submit-row {
  display: flex;
  justify-content: flex-end;
}

.form-error {
  margin: 0;
  padding: 0.7rem 0.85rem;
  border: 1px solid #ffd2d2;
  border-radius: 0.65rem;
  color: #a52e2e;
  background: #fff5f5;
  font-size: 0.85rem;
}

.created-decision {
  margin-top: 0.25rem;
}
</style>
