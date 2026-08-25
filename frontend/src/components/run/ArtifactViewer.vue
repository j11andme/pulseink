<script setup lang="ts">
import { computed, ref } from "vue";
import type { ArtifactResponse } from "../../api/run";
import { formatDateTime } from "../../utils/date";

const props = defineProps<{
  artifacts: ArtifactResponse[];
}>();

const showInvalidated = ref(false);

const visibleArtifacts = computed(() =>
  props.artifacts.filter(
    (artifact) => showInvalidated.value || artifact.status === "VALID"
  )
);

const typeLabels: Record<string, string> = {
  PLAN: "执行计划",
  EVIDENCE_PACK: "证据包",
  CONTENT_STRATEGY: "内容策略",
  CONTENT_DRAFT: "内容草稿",
  REVIEW_REPORT: "Review 报告"
};

const knownTypes = new Set(Object.keys(typeLabels));

function displayValue(value: unknown): string {
  if (value === null || value === undefined) {
    return "-";
  }
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return JSON.stringify(value, null, 2);
}

function contentEntries(artifact: ArtifactResponse): Array<[string, string]> {
  return Object.entries(artifact.content ?? {}).map(([key, value]) => [key, displayValue(value)]);
}
</script>

<template>
  <section class="artifact-viewer">
    <div class="artifact-toolbar">
      <h3>Artifact</h3>
      <button
        class="secondary-button"
        type="button"
        data-testid="artifact-toggle-invalidated"
        @click="showInvalidated = !showInvalidated"
      >
        {{ showInvalidated ? "仅看有效" : "查看失效历史" }}
      </button>
    </div>

    <div v-if="visibleArtifacts.length === 0" class="artifact-empty" data-testid="artifact-empty">
      暂无 Artifact。
    </div>
    <div v-else class="artifact-list">
      <article
        v-for="artifact in visibleArtifacts"
        :key="artifact.artifactId"
        class="artifact-card"
        data-testid="artifact-card"
      >
        <header class="artifact-head">
          <div>
            <strong>{{ typeLabels[artifact.type] ?? artifact.type }}</strong>
            <span class="artifact-id">{{ artifact.artifactId }} · v{{ artifact.artifactVersion }}</span>
          </div>
          <span
            class="artifact-status"
            :class="{ 'is-invalidated': artifact.status !== 'VALID' }"
          >
            {{ artifact.status }}
          </span>
        </header>
        <dl class="artifact-meta">
          <div>
            <dt>taskId</dt>
            <dd>{{ artifact.taskId }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(artifact.createdAt) }}</dd>
          </div>
          <div v-if="artifact.sourceRefs.length > 0">
            <dt>引用</dt>
            <dd>{{ artifact.sourceRefs.join(", ") }}</dd>
          </div>
        </dl>

        <div v-if="!knownTypes.has(artifact.type)" class="artifact-json-fallback">
          <pre data-testid="artifact-json-fallback">{{ JSON.stringify(artifact.content, null, 2) }}</pre>
        </div>
        <dl v-else class="artifact-content">
          <div v-for="[key, value] in contentEntries(artifact)" :key="key">
            <dt>{{ key }}</dt>
            <dd>
              <pre v-if="key === 'plan'">{{ value }}</pre>
              <template v-else>{{ value }}</template>
            </dd>
          </div>
        </dl>
      </article>
    </div>
  </section>
</template>

<style scoped>
.artifact-viewer {
  padding: 1rem 1.1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.artifact-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.artifact-toolbar h3 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.artifact-empty {
  padding: 1.25rem;
  border-radius: 0.65rem;
  color: #596277;
  background: #f7f8fb;
  text-align: center;
  font-size: 0.84rem;
}

.artifact-list {
  display: grid;
  gap: 0.6rem;
}

.artifact-card {
  padding: 0.8rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.7rem;
  background: #fbfbfd;
}

.artifact-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.6rem;
}

.artifact-head div {
  display: grid;
  gap: 0.2rem;
}

.artifact-head strong {
  color: #4a3f9c;
  font-size: 0.9rem;
}

.artifact-id {
  color: #667085;
  font-size: 0.72rem;
}

.artifact-status {
  padding: 0.2rem 0.5rem;
  border-radius: 0.45rem;
  color: #166b4b;
  background: #e8f7f0;
  font-size: 0.7rem;
  font-weight: 800;
}

.artifact-status.is-invalidated {
  color: #7a3c12;
  background: #fdf1e5;
}

.artifact-meta,
.artifact-content {
  display: grid;
  gap: 0.35rem;
  margin: 0.6rem 0 0;
}

.artifact-meta div,
.artifact-content div {
  padding: 0.45rem 0.6rem;
  border-radius: 0.5rem;
  background: #f0f2f7;
}

.artifact-meta dt,
.artifact-content dt {
  color: #667085;
  font-size: 0.68rem;
  font-weight: 700;
}

.artifact-meta dd,
.artifact-content dd {
  margin: 0.2rem 0 0;
  color: #3d465a;
  font-size: 0.78rem;
  overflow-wrap: anywhere;
}

pre {
  margin: 0;
  color: #253047;
  font-size: 0.76rem;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
