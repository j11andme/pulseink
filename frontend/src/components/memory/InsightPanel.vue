<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../../api/http";
import {
  decideInsight,
  generateInsightCandidate,
  listInsightsByCampaign,
  searchApprovedInsights,
  type ApprovedInsightHit,
  type InsightResponse
} from "../../api/memory";
import { useAuthStore } from "../../stores/auth";
import { formatDateTime } from "../../utils/date";

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 30;

const props = defineProps<{
  runId: number | null;
  campaignId: number;
}>();

const auth = useAuthStore();
const router = useRouter();
const canWrite = computed(
  () => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN"
);

const insights = ref<InsightResponse[]>([]);
const loading = ref(false);
const errorMessage = ref("");

const generating = ref(false);
const generateError = ref("");

const decisionChoices = reactive<Record<number, "APPROVE" | "REJECT">>({});
const decisionComments = reactive<Record<number, string>>({});
const deciding = ref<number | null>(null);
const decisionError = ref("");

const searchQuery = ref("");
const searchHits = ref<ApprovedInsightHit[]>([]);
const searchError = ref("");
const searching = ref(false);

let pollTimer: ReturnType<typeof setInterval> | undefined;
let pollAttempts = 0;

const statusLabels: Record<string, string> = {
  PENDING: "待人工决定",
  APPROVED: "已批准",
  REJECTED: "已拒绝"
};

const indexLabels: Record<string, string> = {
  NOT_INDEXED: "未索引",
  INDEXING: "索引中",
  INDEXED: "已索引",
  FAILED: "索引失败"
};

function clearPolling() {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
  pollAttempts = 0;
}

async function loadInsights() {
  if (props.campaignId <= 0) {
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    insights.value = await listInsightsByCampaign(
      auth.accessToken!,
      props.campaignId
    );
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

async function generateCandidate() {
  if (!canWrite.value || generating.value || props.runId === null) {
    return;
  }
  generating.value = true;
  generateError.value = "";
  try {
    await generateInsightCandidate(auth.accessToken!, props.runId);
    await loadInsights();
  } catch (error) {
    generateError.value =
      error instanceof ApiError ? error.message : "生成 Candidate 失败，请稍后重试";
  } finally {
    generating.value = false;
  }
}

async function submitDecision(insightId: number) {
  if (
    !canWrite.value ||
    deciding.value !== null ||
    !(insightId in decisionChoices)
  ) {
    return;
  }
  deciding.value = insightId;
  decisionError.value = "";
  try {
    const decision = decisionChoices[insightId] ?? "APPROVE";
    const comment = decisionComments[insightId]?.trim() || undefined;
    const updated = await decideInsight(
      auth.accessToken!,
      insightId,
      decision,
      comment
    );
    insights.value = insights.value.map((item) =>
      item.id === insightId ? updated : item
    );
    delete decisionChoices[insightId];
    delete decisionComments[insightId];
    if (decision === "APPROVE") {
      startIndexPolling(insightId);
    }
  } catch (error) {
    decisionError.value =
      error instanceof ApiError ? error.message : "决定提交失败，请稍后重试";
  } finally {
    deciding.value = null;
  }
}

function startIndexPolling(insightId: number) {
  clearPolling();
  pollTimer = setInterval(async () => {
    pollAttempts += 1;
    try {
      const latest = await listInsightsByCampaign(
        auth.accessToken!,
        props.campaignId
      );
      insights.value = latest;
      const target = latest.find((item) => item.id === insightId);
      if (
        target === undefined ||
        target.indexStatus === "INDEXED" ||
        target.indexStatus === "FAILED" ||
        pollAttempts >= POLL_MAX_ATTEMPTS
      ) {
        clearPolling();
      }
    } catch {
      if (pollAttempts >= POLL_MAX_ATTEMPTS) {
        clearPolling();
      }
    }
  }, POLL_INTERVAL_MS);
}

async function submitSearch() {
  if (!searchQuery.value.trim() || searching.value) {
    return;
  }
  searching.value = true;
  searchError.value = "";
  searchHits.value = [];
  try {
    searchHits.value = await searchApprovedInsights(
      auth.accessToken!,
      searchQuery.value.trim(),
      undefined,
      0
    );
  } catch (error) {
    searchError.value =
      error instanceof ApiError ? error.message : "检索失败，请稍后重试";
  } finally {
    searching.value = false;
  }
}

watch(() => props.campaignId, () => {
  clearPolling();
  void loadInsights();
}, { immediate: true });

onBeforeUnmount(clearPolling);
</script>

<template>
  <section class="insight-panel">
    <div v-if="runId === null" class="panel-state" data-testid="insight-no-run">
      请先启动 Run：生成 Insight Candidate 需要该 Run 的 Content / Publication / Feedback 事实。
    </div>
    <template v-else>
      <div class="panel-toolbar">
        <h3>Insights（三层记忆 · 人工治理）</h3>
        <button
          class="secondary-button"
          type="button"
          data-testid="insight-reload"
          @click="loadInsights"
        >
          刷新
        </button>
      </div>

      <div v-if="!canWrite" class="viewer-hint">
        Viewer 只读：可以查看 Candidate 与已批准洞察，不能生成或决定。
      </div>
      <template v-else>
        <button
          class="primary-button generate-button"
          type="button"
          data-testid="insight-generate"
          :disabled="generating"
          @click="generateCandidate"
        >
          {{ generating ? "正在生成…" : "生成 PENDING Candidate" }}
        </button>
        <p v-if="generateError" class="form-error" role="alert">{{ generateError }}</p>
      </template>

      <div v-if="loading" class="panel-state" data-testid="insight-loading">正在加载…</div>
      <div v-else-if="errorMessage" class="panel-state panel-error" data-testid="insight-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadInsights">重试</button>
      </div>
      <div v-else-if="insights.length === 0" class="panel-state" data-testid="insight-empty">
        暂无 Insight Candidate。生成后仍需人工批准，批准成功才进入长期检索。
      </div>
      <ul v-else class="insight-list">
        <li v-for="item in insights" :key="item.id" class="insight-card">
          <header>
            <div>
              <strong>{{ item.title }}</strong>
              <span>{{ item.category }} · {{ statusLabels[item.status] ?? item.status }}</span>
            </div>
            <span class="index-status" :data-index="item.indexStatus">
              {{ item.indexStatus }}（{{ indexLabels[item.indexStatus] ?? item.indexStatus }}）
            </span>
          </header>
          <p class="insight-text">{{ item.insightText }}</p>
          <dl class="insight-meta">
            <div>
              <dt>scope</dt>
              <dd>{{ item.scopeType }} / {{ item.scopeValue }}</dd>
            </div>
            <div>
              <dt>channels</dt>
              <dd>{{ item.applicableChannels.join(", ") }}</dd>
            </div>
            <div>
              <dt>confidence</dt>
              <dd>{{ item.confidence.toFixed(2) }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ formatDateTime(item.createdAt) }}</dd>
            </div>
          </dl>
          <div v-if="item.limitations.length > 0" class="limitations">
            <strong>limitations</strong>
            <ul>
              <li v-for="limitation in item.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </div>
          <div v-if="item.evidenceRefs.length > 0" class="evidence-refs">
            <strong>evidence refs</strong>
            <ul>
              <li v-for="ref in item.evidenceRefs" :key="`${ref.contentVersionId}-${ref.publicationId}`">
                contentVersionId {{ ref.contentVersionId }} · publicationId {{ ref.publicationId }}
              </li>
            </ul>
          </div>
          <p v-if="item.reviewComment" class="review-comment">
            决定意见：{{ item.reviewComment }}
          </p>

          <div
            v-if="item.status === 'PENDING' && canWrite"
            class="decision-row"
            data-testid="insight-decision-form"
          >
            <select v-model="decisionChoices[item.id]" data-testid="insight-decision">
              <option disabled value="">选择决定</option>
              <option value="APPROVE">APPROVE</option>
              <option value="REJECT">REJECT</option>
            </select>
            <input
              v-model="decisionComments[item.id]"
              type="text"
              data-testid="insight-decision-comment"
              placeholder="决定意见（可选）"
            />
            <button
              class="primary-button"
              type="button"
              data-testid="insight-decide-submit"
              :disabled="deciding === item.id || !decisionChoices[item.id]"
              @click="submitDecision(item.id)"
            >
              {{ deciding === item.id ? "提交中…" : "提交决定" }}
            </button>
          </div>
        </li>
      </ul>
      <p v-if="decisionError" class="form-error" role="alert">{{ decisionError }}</p>

      <section class="approved-search">
        <h4>已批准洞察检索（只读）</h4>
        <div class="search-row">
          <input
            v-model="searchQuery"
            type="text"
            data-testid="insight-search-input"
            placeholder="检索长期记忆中的已批准洞察"
            @keydown.enter.prevent="submitSearch"
          />
          <button
            class="primary-button"
            type="button"
            data-testid="insight-search-submit"
            :disabled="searching || !searchQuery.trim()"
            @click="submitSearch"
          >
            {{ searching ? "检索中…" : "检索" }}
          </button>
        </div>
        <p v-if="searchError" class="form-error" role="alert">{{ searchError }}</p>
        <div v-if="searchHits.length > 0" class="hit-list">
          <article v-for="hit in searchHits" :key="hit.insightId">
            <strong>{{ hit.title }}</strong>
            <p>{{ hit.insightText }}</p>
            <span>
              source campaign {{ hit.sourceCampaignId }} · confidence {{ hit.confidence.toFixed(2) }}
            </span>
          </article>
        </div>
        <p v-else-if="searchQuery && !searching" class="panel-state">
          暂无已批准洞察命中。
        </p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.insight-panel {
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
.approved-search h4 {
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

.viewer-hint {
  padding: 0.7rem 0.85rem;
  border-radius: 0.6rem;
  color: #7a3c12;
  background: #fdf1e5;
  font-size: 0.82rem;
}

.generate-button {
  justify-self: start;
}

.form-error {
  margin: 0;
  padding: 0.7rem 0.85rem;
  border: 1px solid #ffd2d2;
  border-radius: 0.65rem;
  color: #a52e2e;
  background: #fff5f5;
  font-size: 0.84rem;
}

.insight-list {
  display: grid;
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.insight-card {
  display: grid;
  gap: 0.6rem;
  padding: 0.9rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.75rem;
  background: #fbfbfd;
}

.insight-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.6rem;
}

.insight-card header div {
  display: grid;
  gap: 0.2rem;
}

.insight-card header strong {
  color: #172033;
  font-size: 0.95rem;
}

.insight-card header span {
  color: #667085;
  font-size: 0.72rem;
}

.index-status {
  padding: 0.25rem 0.55rem;
  border-radius: 0.45rem;
  color: #4a3f9c;
  background: #f0f0ff;
  font-size: 0.72rem;
  font-weight: 800;
}

.index-status[data-index="INDEXED"] {
  color: #166b4b;
  background: #e8f7f0;
}

.index-status[data-index="FAILED"] {
  color: #a52e2e;
  background: #fff5f5;
}

.insight-text {
  margin: 0;
  color: #3d465a;
  line-height: 1.7;
  font-size: 0.85rem;
}

.insight-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 0.4rem;
  margin: 0;
}

.insight-meta div {
  padding: 0.45rem 0.6rem;
  border-radius: 0.5rem;
  background: #f0f2f7;
}

.insight-meta dt {
  color: #667085;
  font-size: 0.68rem;
  font-weight: 700;
}

.insight-meta dd {
  margin: 0.2rem 0 0;
  color: #172033;
  font-size: 0.78rem;
}

.limitations,
.evidence-refs {
  display: grid;
  gap: 0.3rem;
  color: #596277;
  font-size: 0.76rem;
}

.limitations ul,
.evidence-refs ul {
  margin: 0;
  padding-left: 1.1rem;
}

.review-comment {
  margin: 0;
  color: #7a3c12;
  font-size: 0.78rem;
}

.decision-row {
  display: grid;
  grid-template-columns: 9rem minmax(0, 1fr) auto;
  gap: 0.5rem;
  align-items: center;
}

.decision-row select,
.decision-row input,
.search-row input {
  width: 100%;
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
}

.approved-search {
  display: grid;
  gap: 0.7rem;
  padding-top: 0.9rem;
  border-top: 1px solid #e4e8f0;
}

.search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.6rem;
}

.hit-list {
  display: grid;
  gap: 0.5rem;
}

.hit-list article {
  padding: 0.7rem 0.8rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.6rem;
  background: #fbfbfd;
}

.hit-list strong {
  color: #172033;
  font-size: 0.85rem;
}

.hit-list p {
  margin: 0.35rem 0;
  color: #3d465a;
  font-size: 0.8rem;
}

.hit-list span {
  color: #667085;
  font-size: 0.72rem;
}
</style>
