<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../api/http";
import {
  listKnowledgeDocuments,
  retryKnowledgeDocument,
  searchKnowledge,
  uploadKnowledgeDocument,
  type KnowledgeDocumentItem,
  type SearchTestResponse
} from "../api/knowledge";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";
import { formatDateTime } from "../utils/date";

const PAGE_SIZE = 20;
const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 30;

const router = useRouter();
const auth = useAuthStore();
const canWrite = computed(
  () => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN"
);

const knowledgeTypes = ["PRODUCT", "BRAND_GUIDELINE", "CHANNEL_RULE", "APPROVED_EXAMPLE"];
const authorities = ["OFFICIAL", "VERIFIED", "REFERENCE"];
const statuses = ["PENDING", "PROCESSING", "ACTIVE", "FAILED"];

const documents = ref<KnowledgeDocumentItem[]>([]);
const total = ref(0);
const loading = ref(true);
const errorMessage = ref("");
const filterStatus = ref("");
const filterType = ref("");
const page = ref(0);

const selectedFile = ref<File | null>(null);
const uploadType = ref("PRODUCT");
const uploadAuthority = ref("OFFICIAL");
const uploadError = ref("");
const uploadResult = ref("");
const isUploading = ref(false);

const searchQuery = ref("");
const searchResult = ref<SearchTestResponse | null>(null);
const searchError = ref("");
const isSearching = ref(false);

let pollTimer: ReturnType<typeof setInterval> | undefined;
let pollAttempts = 0;

const hasProcessingDocuments = computed(() =>
  documents.value.some(
    (document) => document.status === "PENDING" || document.status === "PROCESSING"
  )
);

const totalPages = computed(() => Math.max(0, Math.ceil(total.value / PAGE_SIZE)));
const canPrev = computed(() => page.value > 0);
const canNext = computed(() => (page.value + 1) * PAGE_SIZE < total.value);

function clearPolling() {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
}

async function loadDocuments() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const response = await listKnowledgeDocuments(auth.accessToken!, {
      status: filterStatus.value || undefined,
      type: filterType.value || undefined,
      page: page.value,
      size: PAGE_SIZE
    });
    documents.value = response.items;
    total.value = response.total;
    ensurePolling();
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "加载失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: "/knowledge" }
      });
    }
  } finally {
    loading.value = false;
  }
}

function ensurePolling() {
  if (hasProcessingDocuments.value && pollTimer === undefined) {
    pollAttempts = 0;
    pollTimer = setInterval(async () => {
      pollAttempts += 1;
      await loadDocuments();
      if (!hasProcessingDocuments.value || pollAttempts >= POLL_MAX_ATTEMPTS) {
        clearPolling();
      }
    }, POLL_INTERVAL_MS);
  } else if (!hasProcessingDocuments.value) {
    clearPolling();
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  selectedFile.value = input.files?.[0] ?? null;
  uploadError.value = "";
}

async function submitUpload() {
  if (!selectedFile.value || isUploading.value) {
    return;
  }
  isUploading.value = true;
  uploadError.value = "";
  uploadResult.value = "";
  try {
    const result = await uploadKnowledgeDocument(
      auth.accessToken!,
      selectedFile.value,
      uploadType.value,
      uploadAuthority.value
    );
    uploadResult.value =
      `上传已受理：文档 #${result.documentId} / ${result.sourceId} / ${result.status}`;
    selectedFile.value = null;
    page.value = 0;
    await loadDocuments();
  } catch (error) {
    uploadError.value =
      error instanceof ApiError ? error.message : "上传失败，请稍后重试";
  } finally {
    isUploading.value = false;
  }
}

async function retryDocument(documentId: number) {
  try {
    await retryKnowledgeDocument(auth.accessToken!, documentId);
    await loadDocuments();
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "重试失败，请稍后重试";
  }
}

function applyFilters() {
  page.value = 0;
  searchResult.value = null;
  void loadDocuments();
}

function goToPage(nextPage: number) {
  page.value = nextPage;
  void loadDocuments();
}

async function submitSearch() {
  if (!searchQuery.value.trim() || isSearching.value) {
    return;
  }
  isSearching.value = true;
  searchError.value = "";
  searchResult.value = null;
  try {
    searchResult.value = await searchKnowledge(auth.accessToken!, {
      query: searchQuery.value.trim(),
      types: [],
      authorities: [],
      updatedAfter: null,
      topK: 5
    });
  } catch (error) {
    searchError.value =
      error instanceof ApiError ? error.message : "检索失败，请稍后重试";
  } finally {
    isSearching.value = false;
  }
}

onMounted(loadDocuments);
onBeforeUnmount(clearPolling);
</script>

<template>
  <AppShell active-route="knowledge">
    <div class="knowledge-view">
      <div class="knowledge-header">
        <div>
          <p class="eyebrow">KNOWLEDGE</p>
          <h1>知识库</h1>
          <p>
            上传产品资料、品牌规范、渠道规则与优秀案例，经解析、切片、Embedding 后进入
            Elasticsearch 混合检索。页面不显示原文件目录、ES 向量、内部索引名或 Embedding 数组。
          </p>
        </div>
      </div>

      <section v-if="canWrite" class="knowledge-card upload-card">
        <h2>上传文档</h2>
        <div class="upload-grid">
          <input
            type="file"
            data-testid="knowledge-file"
            accept=".pdf,.docx,.md,.txt,text/markdown,text/plain"
            @change="onFileChange"
          />
          <label>
            类型
            <select v-model="uploadType" data-testid="knowledge-type">
              <option v-for="type in knowledgeTypes" :key="type" :value="type">
                {{ type }}
              </option>
            </select>
          </label>
          <label>
            权威级别
            <select v-model="uploadAuthority" data-testid="knowledge-authority">
              <option v-for="authority in authorities" :key="authority" :value="authority">
                {{ authority }}
              </option>
            </select>
          </label>
          <button
            class="primary-button"
            type="button"
            data-testid="knowledge-upload-submit"
            :disabled="isUploading || !selectedFile"
            @click="submitUpload"
          >
            {{ isUploading ? "正在上传…" : "上传并解析" }}
          </button>
        </div>
        <p v-if="uploadResult" class="form-success" data-testid="knowledge-upload-result">
          {{ uploadResult }}
        </p>
        <p v-if="uploadError" class="form-error" role="alert">{{ uploadError }}</p>
      </section>

      <section class="knowledge-card">
        <div class="list-toolbar">
          <h2>文档列表</h2>
          <button
            class="secondary-button"
            type="button"
            data-testid="knowledge-reload"
            @click="loadDocuments"
          >
            刷新
          </button>
          <div class="filter-row">
            <label>
              状态
              <select v-model="filterStatus" data-testid="knowledge-status-filter" @change="applyFilters">
                <option value="">全部</option>
                <option v-for="status in statuses" :key="status" :value="status">
                  {{ status }}
                </option>
              </select>
            </label>
            <label>
              类型
              <select v-model="filterType" data-testid="knowledge-type-filter" @change="applyFilters">
                <option value="">全部</option>
                <option v-for="type in knowledgeTypes" :key="type" :value="type">
                  {{ type }}
                </option>
              </select>
            </label>
          </div>
        </div>

        <div v-if="loading" class="view-state" data-testid="knowledge-loading">正在加载…</div>
        <div v-else-if="errorMessage" class="view-state view-error" data-testid="knowledge-error">
          <p>{{ errorMessage }}</p>
          <button class="secondary-button" type="button" @click="loadDocuments">
            重试
          </button>
        </div>
        <div
          v-else-if="documents.length === 0"
          class="view-state"
          data-testid="knowledge-empty"
        >
          暂无知识文档。
        </div>
        <template v-else>
          <div class="document-table" data-testid="knowledge-document-list">
            <div class="document-row document-head">
              <span>文件名</span>
              <span>类型 / 权威</span>
              <span>状态</span>
              <span>切片</span>
              <span>更新时间</span>
              <span>操作</span>
            </div>
            <div
              v-for="document in documents"
              :key="document.documentId"
              class="document-row"
            >
              <strong>{{ document.originalFilename }}</strong>
              <span>{{ document.knowledgeType }} / {{ document.authority }}</span>
              <span class="document-status" :data-status="document.status">
                {{ document.status }}
                <small v-if="document.failureCode">{{ document.failureCode }}</small>
              </span>
              <span>{{ document.chunkCount }}</span>
              <span>{{ formatDateTime(document.updatedAt) }}</span>
              <span>
                <button
                  v-if="canWrite && document.status === 'FAILED'"
                  class="secondary-button"
                  type="button"
                  data-testid="knowledge-retry-document"
                  @click="retryDocument(document.documentId)"
                >
                  Retry
                </button>
                <span v-else>-</span>
              </span>
            </div>
          </div>
          <div class="pagination-row">
            <button
              class="secondary-button"
              type="button"
              data-testid="knowledge-page-prev"
              :disabled="!canPrev"
              @click="goToPage(page - 1)"
            >
              上一页
            </button>
            <span>第 {{ page + 1 }} / {{ totalPages || 1 }} 页 · 共 {{ total }} 条</span>
            <button
              class="secondary-button"
              type="button"
              data-testid="knowledge-page-next"
              :disabled="!canNext"
              @click="goToPage(page + 1)"
            >
              下一页
            </button>
          </div>
        </template>
      </section>

      <section class="knowledge-card">
        <h2>Search Test</h2>
        <div class="search-row">
          <input
            v-model="searchQuery"
            type="text"
            data-testid="knowledge-search-input"
            placeholder="输入查询，查看混合检索与 Evidence"
            @keydown.enter.prevent="submitSearch"
          />
          <button
            class="primary-button"
            type="button"
            data-testid="knowledge-search-submit"
            :disabled="isSearching || !searchQuery.trim()"
            @click="submitSearch"
          >
            {{ isSearching ? "检索中…" : "检索" }}
          </button>
        </div>
        <p v-if="searchError" class="form-error" role="alert">{{ searchError }}</p>
        <div
          v-if="searchResult"
          class="search-result"
          data-testid="knowledge-search-result"
        >
          <div class="search-meta">
            <span>retrievalMode：<strong>{{ searchResult.retrievalMode }}</strong></span>
            <span v-if="searchResult.degradedReasonCode">
              degradedReason：<strong>{{ searchResult.degradedReasonCode }}</strong>
            </span>
          </div>
          <div v-if="searchResult.evidence.length === 0" class="view-state">
            未检索到 Evidence。
          </div>
          <ul v-else class="evidence-list">
            <li v-for="evidence in searchResult.evidence" :key="`${evidence.sourceId}-${evidence.title}`">
              <div class="evidence-head">
                <strong>{{ evidence.title }}</strong>
                <span>score {{ evidence.score.toFixed(3) }}</span>
              </div>
              <p>{{ evidence.snippet }}</p>
              <div class="evidence-meta">
                <span>{{ evidence.sourceId }}</span>
                <span>{{ evidence.type }} / {{ evidence.authority }}</span>
                <span v-if="evidence.heading">{{ evidence.heading }}</span>
                <span v-if="evidence.updatedAt">{{ formatDateTime(evidence.updatedAt) }}</span>
              </div>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.knowledge-view {
  display: grid;
  gap: 1.5rem;
}

.knowledge-header h1 {
  margin: 0;
  color: #172033;
  font-size: 2rem;
  letter-spacing: -0.035em;
}

.knowledge-header p:not(.eyebrow) {
  max-width: 52rem;
  margin: 0.75rem 0 0;
  color: #596277;
  line-height: 1.8;
}

.knowledge-card {
  padding: clamp(1.2rem, 3vw, 1.75rem);
  border: 1px solid #e4e8f0;
  border-radius: 1.1rem;
  background: #fff;
  box-shadow: 0 1rem 3rem rgba(28, 37, 60, 0.05);
}

.knowledge-card h2 {
  margin: 0 0 1rem;
  color: #172033;
  font-size: 1.1rem;
}

.upload-grid {
  display: grid;
  grid-template-columns: minmax(14rem, 1fr) 11rem 11rem auto;
  align-items: end;
  gap: 0.75rem;
}

.upload-grid label,
.filter-row label {
  display: grid;
  gap: 0.35rem;
  color: #596277;
  font-size: 0.8rem;
  font-weight: 700;
}

.upload-grid input[type="file"] {
  padding: 0.55rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.65rem;
  color: #172033;
  background: #fff;
}

select,
.search-row input {
  width: 100%;
  padding: 0.65rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.65rem;
  outline: none;
  color: #172033;
  background: #fff;
}

.list-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}

.filter-row {
  display: flex;
  gap: 0.6rem;
}

.view-state {
  padding: 2rem;
  border-radius: 0.9rem;
  color: #596277;
  background: #f7f8fb;
  text-align: center;
}

.view-error {
  display: grid;
  justify-items: center;
  gap: 0.75rem;
  color: #a52e2e;
  background: #fff5f5;
}

.form-success {
  margin: 0.75rem 0 0;
  padding: 0.7rem 0.85rem;
  border-radius: 0.65rem;
  color: #166b4b;
  background: #e8f7f0;
  font-size: 0.85rem;
}

.form-error {
  margin: 0.75rem 0 0;
  padding: 0.7rem 0.85rem;
  border: 1px solid #ffd2d2;
  border-radius: 0.65rem;
  color: #a52e2e;
  background: #fff5f5;
  font-size: 0.85rem;
}

.document-table {
  display: grid;
  gap: 0.35rem;
}

.document-row {
  display: grid;
  grid-template-columns: minmax(10rem, 1.3fr) 0.9fr 0.75fr 0.35fr 0.9fr 0.5fr;
  align-items: center;
  gap: 0.6rem;
  padding: 0.65rem 0.75rem;
  border-radius: 0.6rem;
  color: #3d465a;
  background: #fbfbfd;
  font-size: 0.8rem;
}

.document-head {
  color: #667085;
  background: #f0f2f7;
  font-size: 0.72rem;
  font-weight: 800;
}

.document-row strong {
  overflow: hidden;
  color: #172033;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-status {
  display: grid;
  gap: 0.15rem;
  color: #4a3f9c;
  font-weight: 800;
}

.document-status small {
  color: #a52e2e;
  font-size: 0.7rem;
  font-weight: 600;
}

.pagination-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.8rem;
  margin-top: 1rem;
  color: #596277;
  font-size: 0.82rem;
}

.search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.6rem;
}

.search-result {
  margin-top: 1rem;
}

.search-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  color: #596277;
  font-size: 0.8rem;
}

.evidence-list {
  display: grid;
  gap: 0.55rem;
  margin: 0.9rem 0 0;
  padding: 0;
  list-style: none;
}

.evidence-list li {
  padding: 0.75rem 0.9rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.7rem;
  background: #fbfbfd;
}

.evidence-head {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  color: #172033;
  font-size: 0.88rem;
}

.evidence-list p {
  margin: 0.4rem 0 0;
  color: #3d465a;
  line-height: 1.7;
}

.evidence-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.45rem;
  color: #667085;
  font-size: 0.72rem;
}

@media (max-width: 980px) {
  .upload-grid,
  .document-row {
    grid-template-columns: 1fr;
  }

  .list-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
