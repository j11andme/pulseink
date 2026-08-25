<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  getRunContents,
  type ContentItemResponse,
  type ContentVersionResponse
} from "../../api/content";
import { ApiError } from "../../api/http";
import {
  getPublication,
  listPublicationsByRun,
  publishContent,
  returnPublicationToEditing,
  type PublicationResponse
} from "../../api/publication";
import { useAuthStore } from "../../stores/auth";
import { formatDateTime } from "../../utils/date";

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_ATTEMPTS = 30;
const NON_TERMINAL_STATUSES = new Set(["PENDING", "SENDING", "RETRY_WAIT"]);

const props = defineProps<{
  runId: number | null;
  campaignChannels: string[];
}>();

const emit = defineEmits<{
  (event: "return-to-editing", runId: number): void;
}>();

const auth = useAuthStore();
const router = useRouter();
const canWrite = computed(
  () => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN"
);

const contents = ref<ContentItemResponse[]>([]);
const publications = ref<PublicationResponse[]>([]);
const loading = ref(false);
const errorMessage = ref("");

const selectedContentId = ref<number | null>(null);
const selectedVersionId = ref<number | null>(null);
const selectedChannel = ref("");
const publishError = ref("");
const isPublishing = ref(false);
const recoveringPublicationId = ref<number | null>(null);

const timers = new Map<number, ReturnType<typeof setInterval>>();
const pollAttempts = new Map<number, number>();

const approvedVersions = computed<ContentVersionResponse[]>(() => {
  const approvedIds = new Set<number>();
  for (const item of contents.value) {
    for (const approval of item.approvals) {
      approvedIds.add(approval.contentVersionId);
    }
  }
  return contents.value.flatMap((item) =>
    item.versions.filter((version) => approvedIds.has(version.id))
  );
});

const eligibleItems = computed(() =>
  contents.value.filter((item) =>
    item.versions.some((version) =>
      approvedVersions.value.some((approved) => approved.id === version.id)
    )
  )
);

const selectedItem = computed(
  () => contents.value.find((item) => item.id === selectedContentId.value) ?? null
);

function latestApprovedVersion(item: ContentItemResponse | undefined) {
  if (item === undefined) {
    return undefined;
  }
  const approvedIds = new Set(
    item.approvals.map((approval) => approval.contentVersionId)
  );
  return [...item.versions]
    .filter((version) => approvedIds.has(version.id))
    .sort((left, right) => right.versionNo - left.versionNo)[0];
}

const statusLabels: Record<string, string> = {
  PENDING: "待发送",
  SENDING: "发送中",
  PUBLISHED: "已发布",
  RETRY_WAIT: "等待重试",
  FAILED: "失败"
};

function clearTimers() {
  for (const timer of timers.values()) {
    clearInterval(timer);
  }
  timers.clear();
  pollAttempts.clear();
}

async function loadPublications() {
  if (props.runId === null) {
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    const [contentResponse, publicationResponse] = await Promise.all([
      getRunContents(auth.accessToken!, props.runId),
      listPublicationsByRun(auth.accessToken!, props.runId)
    ]);
    contents.value = contentResponse.contents;
    publications.value = publicationResponse;
    for (const publication of publicationResponse) {
      if (NON_TERMINAL_STATUSES.has(publication.status)) {
        pollPublication(publication.id);
      }
    }
    const firstEligible = contentResponse.contents.find((item) =>
      item.versions.some((version) =>
        approvedVersions.value.some((approved) => approved.id === version.id)
      )
    );
    if (
      selectedContentId.value === null ||
      !eligibleItems.value.some((item) => item.id === selectedContentId.value)
    ) {
      selectedContentId.value = firstEligible?.id ?? null;
    }
    const current = contents.value.find(
      (item) => item.id === selectedContentId.value
    );
    const currentApproved = latestApprovedVersion(current);
    if (
      selectedVersionId.value === null ||
      !approvedVersions.value.some((version) => version.id === selectedVersionId.value)
    ) {
      selectedVersionId.value = currentApproved?.id ?? approvedVersions.value[0]?.id ?? null;
    }
    selectedChannel.value = props.campaignChannels[0] ?? "";
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

async function submitPublish() {
  if (
    !canWrite.value ||
    isPublishing.value ||
    selectedContentId.value === null ||
    selectedVersionId.value === null ||
    !selectedChannel.value
  ) {
    return;
  }
  isPublishing.value = true;
  publishError.value = "";
  try {
    const created = await publishContent(
      auth.accessToken!,
      selectedContentId.value,
      selectedVersionId.value,
      selectedChannel.value
    );
    publications.value = [
      created,
      ...publications.value.filter((publication) => publication.id !== created.id)
    ];
    if (NON_TERMINAL_STATUSES.has(created.status)) {
      pollPublication(created.id);
    }
  } catch (error) {
    publishError.value =
      error instanceof ApiError ? error.message : "发布请求失败，请稍后重试";
  } finally {
    isPublishing.value = false;
  }
}

async function returnToEditing(publication: PublicationResponse) {
  if (!canWrite.value || recoveringPublicationId.value !== null) {
    return;
  }
  recoveringPublicationId.value = publication.id;
  publishError.value = "";
  try {
    await returnPublicationToEditing(auth.accessToken!, publication.id);
    emit("return-to-editing", publication.runId);
  } catch (error) {
    publishError.value =
      error instanceof ApiError ? error.message : "返回修改失败，请稍后重试";
  } finally {
    recoveringPublicationId.value = null;
  }
}

function pollPublication(publicationId: number) {
  clearTimer(publicationId);
  pollAttempts.set(publicationId, 0);
  const timer = setInterval(async () => {
    const attempts = (pollAttempts.get(publicationId) ?? 0) + 1;
    pollAttempts.set(publicationId, attempts);
    try {
      const latest = await getPublication(auth.accessToken!, publicationId);
      publications.value = [
        latest,
        ...publications.value.filter((publication) => publication.id !== publicationId)
      ];
      if (!NON_TERMINAL_STATUSES.has(latest.status) || attempts >= POLL_MAX_ATTEMPTS) {
        clearTimer(publicationId);
      }
    } catch {
      if (attempts >= POLL_MAX_ATTEMPTS) {
        clearTimer(publicationId);
      }
    }
  }, POLL_INTERVAL_MS);
  timers.set(publicationId, timer);
}

function clearTimer(publicationId: number) {
  const timer = timers.get(publicationId);
  if (timer !== undefined) {
    clearInterval(timer);
    timers.delete(publicationId);
  }
  pollAttempts.delete(publicationId);
}

watch(() => props.runId, () => {
  clearTimers();
  selectedContentId.value = null;
  selectedVersionId.value = null;
  void loadPublications();
}, { immediate: true });

watch(selectedContentId, (contentId) => {
  const item = contents.value.find((candidate) => candidate.id === contentId);
  const latestApproved = latestApprovedVersion(item);
  const approvedIds = new Set(
    item?.approvals.map((approval) => approval.contentVersionId) ?? []
  );
  if (!approvedIds.has(selectedVersionId.value ?? -1)) {
    selectedVersionId.value = latestApproved?.id ?? null;
  }
});

onBeforeUnmount(clearTimers);
</script>

<template>
  <section class="publish-panel">
    <div v-if="runId === null" class="panel-state" data-testid="publish-no-run">
      请先启动 Run：在 Run Tab 启动并选择一个 Run，再来发布内容。
    </div>
    <template v-else>
      <div class="panel-toolbar">
        <h3>Sandbox 发布</h3>
        <button
          class="secondary-button"
          type="button"
          data-testid="publish-reload"
          @click="loadPublications"
        >
          刷新
        </button>
      </div>

      <div v-if="loading" class="panel-state" data-testid="publish-loading">正在加载…</div>
      <div v-else-if="errorMessage" class="panel-state panel-error" data-testid="publish-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadPublications">重试</button>
      </div>
      <template v-else>
        <div v-if="!canWrite" class="viewer-hint">Viewer 只读，不能发起发布。</div>
        <div v-else-if="approvedVersions.length === 0" class="panel-state">
          没有可发布的版本：请先在 Content Tab 创建并批准指定版本。
        </div>
        <div v-else class="publish-form">
          <label>
            ContentItem
            <select v-model.number="selectedContentId" data-testid="publish-content-select">
              <option
                v-for="item in eligibleItems"
                :key="item.id"
                :value="item.id"
              >
                #{{ item.id }} · {{ item.taskId }}
              </option>
            </select>
          </label>
          <label>
            已批准版本（Approval 门禁）
            <select v-model.number="selectedVersionId" data-testid="publish-version-select">
              <option
                v-for="version in selectedItem?.versions.filter((v) =>
                  approvedVersions.some((approved) => approved.id === v.id)) ?? approvedVersions"
                :key="version.id"
                :value="version.id"
              >
                v{{ version.versionNo }} · {{ version.origin }} · #{{ version.id }}
              </option>
            </select>
          </label>
          <label>
            渠道（来自 Campaign）
            <select v-model="selectedChannel" data-testid="publish-channel-select">
              <option
                v-for="channel in campaignChannels"
                :key="channel"
                :value="channel"
              >
                {{ channel }}
              </option>
            </select>
          </label>
          <button
            class="primary-button"
            type="button"
            data-testid="publish-submit"
            :disabled="isPublishing || selectedVersionId === null || !selectedChannel"
            @click="submitPublish"
          >
            {{ isPublishing ? "正在提交…" : "发布到 Sandbox" }}
          </button>
        </div>

        <p v-if="publishError" class="form-error" role="alert">{{ publishError }}</p>

        <section class="publication-list">
          <h4>Publication 记录</h4>
          <div v-if="publications.length === 0" class="panel-state">暂无发布记录。</div>
          <ul v-else>
            <li v-for="publication in publications" :key="publication.id">
              <div class="publication-head">
                <strong>#{{ publication.id }} · {{ publication.channel }}</strong>
                <span :class="`status-${publication.status.toLowerCase()}`">
                  {{ publication.status }}（{{ statusLabels[publication.status] ?? publication.status }}）
                </span>
              </div>
              <p>
                contentVersionId {{ publication.contentVersionId }} ·
                attemptCount {{ publication.attemptCount }} ·
                {{ formatDateTime(publication.updatedAt) }}
              </p>
              <p v-if="publication.externalPostId">
                externalPostId：{{ publication.externalPostId }}
              </p>
              <p v-if="publication.publishedAt">
                publishedAt：{{ formatDateTime(publication.publishedAt) }}
              </p>
              <p v-if="publication.status === 'FAILED'" class="failure-text">
                {{ publication.failureCode }}：{{ publication.failureMessage ?? "无详细信息" }}
              </p>
              <button
                v-if="publication.status === 'FAILED' && canWrite"
                class="secondary-button"
                type="button"
                :data-testid="`publication-return-to-editing-${publication.id}`"
                :disabled="recoveringPublicationId !== null"
                @click="returnToEditing(publication)"
              >
                {{ recoveringPublicationId === publication.id ? "正在返回…" : "返回修改" }}
              </button>
            </li>
          </ul>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.publish-panel {
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
.publication-list h4 {
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

.publish-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr)) auto;
  align-items: end;
  gap: 0.7rem;
}

.publish-form label {
  display: grid;
  gap: 0.35rem;
  color: #596277;
  font-size: 0.78rem;
  font-weight: 700;
}

.publish-form select {
  width: 100%;
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
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

.publication-list ul {
  display: grid;
  gap: 0.5rem;
  margin: 0.75rem 0 0;
  padding: 0;
  list-style: none;
}

.publication-list li {
  padding: 0.7rem 0.8rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.65rem;
  color: #3d465a;
  background: #fbfbfd;
  font-size: 0.8rem;
}

.publication-head {
  display: flex;
  justify-content: space-between;
  gap: 0.6rem;
}

.publication-head strong {
  color: #172033;
}

.status-pending,
.status-sending,
.status-retry_wait {
  color: #7a3c12;
  font-weight: 800;
}

.status-published {
  color: #166b4b;
  font-weight: 800;
}

.status-failed {
  color: #a52e2e;
  font-weight: 800;
}

.publication-list p {
  margin: 0.3rem 0 0;
}

.failure-text {
  color: #a52e2e;
}
</style>
