<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  approveContentVersion,
  createContentVersion,
  type ContentItemResponse,
  type ContentVersionResponse
} from "../../api/content";
import { ApiError } from "../../api/http";
import { useAuthStore } from "../../stores/auth";
import { formatDateTime } from "../../utils/date";

const props = defineProps<{
  item: ContentItemResponse;
  runId: number;
}>();

const emit = defineEmits<{
  changed: [];
}>();

const auth = useAuthStore();
const canWrite = computed(
  () => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN"
);

const latestVersion = computed<ContentVersionResponse | undefined>(
  () => [...props.item.versions].sort((left, right) => right.versionNo - left.versionNo)[0]
);

const editText = ref(
  latestVersion.value
    ? JSON.stringify(latestVersion.value.content, null, 2)
    : "{}"
);

const editError = ref("");
const isCreating = ref(false);
const approvalVersionId = ref<number | null>(latestVersion.value?.id ?? null);
const approvalComment = ref("");
const approveError = ref("");
const isApproving = ref(false);

watch(() => props.item.id, () => {
  const latest = latestVersion.value;
  editText.value = latest ? JSON.stringify(latest.content, null, 2) : "{}";
  editError.value = "";
  approvalVersionId.value = latest?.id ?? null;
  approvalComment.value = "";
  approveError.value = "";
});

function parseJsonObject(): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(editText.value) as unknown;
    if (
      typeof parsed !== "object" ||
      parsed === null ||
      Array.isArray(parsed)
    ) {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function createHumanVersion() {
  if (!canWrite.value || isCreating.value) {
    return;
  }
  const content = parseJsonObject();
  if (content === null || Object.keys(content).length === 0) {
    editError.value = "内容必须是非空 JSON Object";
    return;
  }
  isCreating.value = true;
  editError.value = "";
  try {
    await createContentVersion(auth.accessToken!, props.item.id, {
      expectedCurrentVersionNo: props.item.currentVersionNo,
      expectedItemVersion: props.item.itemVersion,
      content,
      sourceRefs: latestVersion.value?.sourceRefs ?? []
    });
    emit("changed");
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      editError.value = `版本冲突：${error.message}。已保留编辑区文本，请基于最新状态再次提交。`;
      emit("changed");
    } else {
      editError.value =
        error instanceof ApiError ? error.message : "创建人工版本失败，请稍后重试";
    }
  } finally {
    isCreating.value = false;
  }
}

async function approveSelectedVersion() {
  if (
    !canWrite.value ||
    isApproving.value ||
    approvalVersionId.value === null
  ) {
    return;
  }
  isApproving.value = true;
  approveError.value = "";
  try {
    await approveContentVersion(auth.accessToken!, props.item.id, {
      contentVersionId: approvalVersionId.value,
      expectedCurrentVersionNo: props.item.currentVersionNo,
      expectedItemVersion: props.item.itemVersion,
      comment: approvalComment.value.trim() || undefined
    });
    approvalComment.value = "";
    emit("changed");
  } catch (error) {
    approveError.value =
      error instanceof ApiError ? error.message : "审批失败，请稍后重试";
  } finally {
    isApproving.value = false;
  }
}
</script>

<template>
  <section class="content-editor">
    <h4>人工编辑与 Approval</h4>
    <div v-if="!canWrite" class="viewer-hint">Viewer 只读，不能创建版本或审批。</div>

    <template v-else>
      <div class="editor-grid">
        <div>
          <label for="content-edit-textarea">
            编辑 JSON（仅允许非空 Object，通用 Map 使用安全文本预览）
          </label>
          <textarea
            id="content-edit-textarea"
            v-model="editText"
            data-testid="content-edit-textarea"
            spellcheck="false"
          />
        </div>
        <div class="editor-side">
          <p>当前版本：v{{ item.currentVersionNo }} · itemVersion {{ item.itemVersion }}</p>
          <p>sourceRefs：{{ latestVersion?.sourceRefs.join(", ") || "无" }}</p>
          <p>origin：{{ latestVersion?.origin ?? "-" }}</p>
          <p v-if="latestVersion?.sourceArtifactId">
            sourceArtifact：{{ latestVersion.sourceArtifactId }} ·
            v{{ latestVersion.sourceArtifactVersion }}
          </p>
          <p>更新时间：{{ formatDateTime(item.updatedAt) }}</p>
        </div>
      </div>

      <p v-if="editError" class="form-error" role="alert" data-testid="content-edit-error">
        {{ editError }}
      </p>

      <div class="approval-row">
        <label>
          审批版本
          <select v-model.number="approvalVersionId" data-testid="content-approve-version">
            <option
              v-for="version in [...item.versions].sort((a, b) => b.versionNo - a.versionNo)"
              :key="version.id"
              :value="version.id"
            >
              v{{ version.versionNo }}（{{ version.origin }}）
            </option>
          </select>
        </label>
        <input
          v-model="approvalComment"
          type="text"
          data-testid="content-approve-comment"
          placeholder="审批意见（可选）"
        />
      </div>
      <p v-if="approveError" class="form-error" role="alert">{{ approveError }}</p>

      <div class="editor-actions">
        <button
          class="primary-button"
          type="button"
          data-testid="content-create-version"
          :disabled="isCreating"
          @click="createHumanVersion"
        >
          {{ isCreating ? "正在创建…" : "创建人工版本" }}
        </button>
        <button
          class="secondary-button"
          type="button"
          data-testid="content-approve-submit"
          :disabled="isApproving || approvalVersionId === null"
          @click="approveSelectedVersion"
        >
          {{ isApproving ? "正在审批…" : "批准该版本" }}
        </button>
      </div>
    </template>

    <ul v-if="item.approvals.length > 0" class="approval-list">
      <li v-for="approval in item.approvals" :key="approval.id">
        Approval #{{ approval.id }} · version {{ approval.contentVersionId }} ·
        {{ approval.comment || "无意见" }} · {{ formatDateTime(approval.createdAt) }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
.content-editor {
  display: grid;
  gap: 0.8rem;
  padding: 1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.85rem;
  background: #fbfbfd;
}

.content-editor h4 {
  margin: 0;
  color: #172033;
  font-size: 1rem;
}

.viewer-hint {
  padding: 0.7rem 0.85rem;
  border-radius: 0.6rem;
  color: #7a3c12;
  background: #fdf1e5;
  font-size: 0.82rem;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(12rem, 0.6fr);
  gap: 0.85rem;
}

.editor-grid label {
  display: grid;
  gap: 0.4rem;
  color: #596277;
  font-size: 0.8rem;
  font-weight: 700;
}

.editor-grid textarea {
  width: 100%;
  min-height: 15rem;
  padding: 0.85rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.7rem;
  color: #172033;
  background: #fff;
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace;
  font-size: 0.8rem;
  line-height: 1.6;
}

.editor-side {
  display: grid;
  gap: 0.4rem;
  color: #596277;
  font-size: 0.78rem;
}

.approval-row {
  display: grid;
  grid-template-columns: 12rem minmax(0, 1fr);
  gap: 0.6rem;
}

.approval-row label {
  display: grid;
  gap: 0.35rem;
  color: #596277;
  font-size: 0.78rem;
  font-weight: 700;
}

.approval-row select,
.approval-row input {
  width: 100%;
  padding: 0.6rem 0.7rem;
  border: 1px solid #d4d8e5;
  border-radius: 0.6rem;
  color: #172033;
  background: #fff;
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
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

.approval-list {
  display: grid;
  gap: 0.35rem;
  margin: 0;
  padding: 0;
  list-style: none;
  color: #596277;
  font-size: 0.78rem;
}
</style>
