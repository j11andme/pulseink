<script setup lang="ts">
import { computed } from "vue";
import type { RunDecision } from "../../api/run";

const props = defineProps<{
  decision: RunDecision;
}>();

const reasonLabels: Record<string, string> = {
  MANUAL_POLICY_OVERRIDE: "手动指定策略",
  LOW_RISK_SINGLE_OUTPUT: "低风险单次输出",
  DECOMPOSABLE_OR_HIGH_RISK: "可并行或高事实风险",
  UNIFIED_CONTEXT_PREFERRED: "优先统一上下文"
};

const policyLabels: Record<string, string> = {
  ADAPTIVE: "AUTO（推荐）",
  DIRECT: "DIRECT（固定）",
  REACT: "REACT（固定）",
  ORCHESTRATED: "ORCHESTRATED（固定）"
};

const snapshotEntries = computed(() =>
  Object.entries(props.decision.featureSnapshot ?? {})
);
</script>

<template>
  <article class="decision-card">
    <header class="decision-header">
      <div>
        <p class="eyebrow">EXECUTION DECISION</p>
        <h3>{{ policyLabels[decision.requestedPolicy] ?? decision.requestedPolicy }}</h3>
      </div>
      <span class="decision-mode">{{ decision.selectedMode ?? "未选择" }}</span>
    </header>

    <ul class="reason-list">
      <li v-for="code in decision.reasonCodes" :key="code" data-testid="decision-reason-code">
        <span>{{ reasonLabels[code] ?? `未知原因（${code}）` }}</span>
        <code>{{ code }}</code>
      </li>
    </ul>

    <dl class="decision-meta">
      <div>
        <dt>策略版本</dt>
        <dd>{{ decision.selectorPolicyVersion ?? "-" }}</dd>
      </div>
      <div>
        <dt>估算 Token 预算</dt>
        <dd>{{ decision.estimatedTokenBudget ?? "-" }}</dd>
      </div>
    </dl>

    <div v-if="snapshotEntries.length > 0" class="feature-snapshot">
      <h4>特征快照</h4>
      <dl>
        <div v-for="[key, value] in snapshotEntries" :key="key">
          <dt>{{ key }}</dt>
          <dd>{{ String(value) }}</dd>
        </div>
      </dl>
    </div>
  </article>
</template>

<style scoped>
.decision-card {
  padding: 1rem 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.decision-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.decision-header h3 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.decision-mode {
  flex: 0 0 auto;
  padding: 0.3rem 0.65rem;
  border-radius: 0.5rem;
  color: #4a3f9c;
  background: #f0f0ff;
  font-size: 0.8rem;
  font-weight: 800;
}

.reason-list {
  display: grid;
  gap: 0.4rem;
  margin: 0.85rem 0 0;
  padding: 0;
  list-style: none;
}

.reason-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
  padding: 0.5rem 0.7rem;
  border-radius: 0.6rem;
  color: #3d465a;
  background: #f7f8fb;
  font-size: 0.82rem;
}

.reason-list code {
  color: #5c5ce6;
  background: #fff;
  font-size: 0.72rem;
}

.decision-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
  margin: 0.75rem 0 0;
}

.decision-meta div,
.feature-snapshot div {
  padding: 0.5rem 0.65rem;
  border-radius: 0.55rem;
  background: #f7f8fb;
}

.decision-meta dt,
.feature-snapshot dt {
  color: #667085;
  font-size: 0.68rem;
  font-weight: 700;
}

.decision-meta dd {
  margin: 0.2rem 0 0;
  color: #172033;
  font-size: 0.8rem;
  font-weight: 700;
}

.feature-snapshot {
  margin-top: 0.75rem;
}

.feature-snapshot h4 {
  margin: 0 0 0.4rem;
  color: #596277;
  font-size: 0.75rem;
}

.feature-snapshot dl {
  display: grid;
  gap: 0.35rem;
  margin: 0;
}

.feature-snapshot dd {
  margin: 0.15rem 0 0;
  color: #3d465a;
  font-size: 0.76rem;
}
</style>
