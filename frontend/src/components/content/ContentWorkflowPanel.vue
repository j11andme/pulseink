<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  getRunContents,
  type ContentItemResponse,
  type ReviewReportResponse
} from "../../api/content";
import { ApiError } from "../../api/http";
import { useAuthStore } from "../../stores/auth";
import ContentEditor from "./ContentEditor.vue";
import { formatDateTime } from "../../utils/date";

const props = defineProps<{
  runId: number | null;
}>();

const auth = useAuthStore();
const router = useRouter();

const contents = ref<ContentItemResponse[]>([]);
const reviews = ref<ReviewReportResponse[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const selectedContentId = ref<number | null>(null);

const selectedContent = computed(
  () => contents.value.find((item) => item.id === selectedContentId.value) ?? null
);

function sortedVersions(item: ContentItemResponse) {
  return [...item.versions].sort((left, right) => right.versionNo - left.versionNo);
}

async function loadContents() {
  if (props.runId === null) {
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    const response = await getRunContents(auth.accessToken!, props.runId);
    contents.value = response.contents;
    reviews.value = response.reviews;
    if (
      selectedContentId.value === null ||
      !response.contents.some((item) => item.id === selectedContentId.value)
    ) {
      selectedContentId.value = response.contents[0]?.id ?? null;
    }
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
}

watch(() => props.runId, () => {
  selectedContentId.value = null;
  void loadContents();
}, { immediate: true });

onBeforeUnmount(() => undefined);
</script>

<template>
  <section class="content-panel">
    <div v-if="runId === null" class="panel-state" data-testid="content-no-run">
      请先启动 Run：在 Run Tab 启动并选择一个 Run，再来查看 Content。
    </div>
    <template v-else>
      <div class="panel-toolbar">
        <h3>Content 与 Review</h3>
        <button
          class="secondary-button"
          type="button"
          data-testid="content-reload"
          @click="loadContents"
        >
          刷新
        </button>
      </div>

      <div v-if="loading" class="panel-state" data-testid="content-loading">正在加载…</div>
      <div v-else-if="errorMessage" class="panel-state panel-error" data-testid="content-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadContents">重试</button>
      </div>
      <div
        v-else-if="contents.length === 0"
        class="panel-state"
        data-testid="content-empty"
      >
        暂无 Content，等待 Agent 产出草稿。
      </div>
      <template v-else>
        <section class="review-section">
          <h4>Review Report</h4>
          <div v-if="reviews.length === 0" class="panel-state">暂无 Review 记录。</div>
          <ul v-else class="review-list">
            <li v-for="report in reviews" :key="report.id">
              <div>
                <strong>{{ report.sourceArtifactId }} · v{{ report.sourceArtifactVersion }}</strong>
                <span :class="report.passed ? 'passed' : 'not-passed'">
                  {{ report.passed ? "PASSED" : "ISSUES" }} · repairRound {{ report.repairRound }}
                </span>
              </div>
              <ul v-if="report.issues.length > 0" class="issue-list">
                <li v-for="issue in report.issues" :key="`${report.id}-${issue.type}`">
                  {{ issue.type }}：{{ issue.message }}
                </li>
              </ul>
            </li>
          </ul>
        </section>

        <div class="content-select-row">
          <label>
            ContentItem
            <select v-model.number="selectedContentId" data-testid="content-item-select">
              <option
                v-for="item in contents"
                :key="item.id"
                :value="item.id"
              >
                #{{ item.id }} · {{ item.taskId }} · currentVersionNo {{ item.currentVersionNo }}
              </option>
            </select>
          </label>
        </div>

        <div v-if="selectedContent" class="version-overview">
          <h4>版本历史</h4>
          <ol>
            <li v-for="version in sortedVersions(selectedContent)" :key="version.id">
              <strong>v{{ version.versionNo }}</strong>
              <span>{{ version.origin }}</span>
              <span>{{ formatDateTime(version.createdAt) }}</span>
              <span v-if="version.sourceArtifactId">{{ version.sourceArtifactId }}</span>
              <pre>{{ JSON.stringify(version.content, null, 2) }}</pre>
            </li>
          </ol>
        </div>

        <ContentEditor
          v-if="selectedContent"
          :item="selectedContent"
          :run-id="runId"
          @changed="loadContents"
        />
      </template>
    </template>
  </section>
</template>

<style scoped>
.content-panel {
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

.panel-toolbar h3,
.review-section h4,
.version-overview h4 {
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

.review-list,
.issue-list,
.version-overview ol {
  display: grid;
  gap: 0.45rem;
  margin: 0.75rem 0 0;
  padding: 0;
  list-style: none;
}

.review-list li,
.version-overview li {
  padding: 0.7rem 0.8rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.6rem;
  background: #fbfbfd;
  color: #3d465a;
  font-size: 0.8rem;
}

.review-list li > div {
  display: flex;
  justify-content: space-between;
  gap: 0.6rem;
}

.passed {
  color: #166b4b;
  font-weight: 800;
}

.not-passed {
  color: #a52e2e;
  font-weight: 800;
}

.issue-list {
  margin-top: 0.5rem;
  padding-left: 0.5rem;
}

.issue-list li {
  padding: 0.3rem 0;
  color: #7a3c12;
}

.content-select-row {
  display: grid;
}

.content-select-row label {
  display: grid;
  gap: 0.35rem;
  color: #596277;
  font-size: 0.8rem;
  font-weight: 700;
}

.content-select-row select {
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
}

.version-overview ol {
  margin-top: 0.5rem;
}

.version-overview li {
  display: grid;
  gap: 0.25rem;
}

.version-overview pre {
  margin: 0.3rem 0 0;
  color: #253047;
  font-size: 0.74rem;
  white-space: pre-wrap;
}
</style>
