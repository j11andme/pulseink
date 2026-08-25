<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../api/http";
import {
  createCampaign,
  listCampaigns,
  type CampaignChannel,
  type CampaignResponse
} from "../api/campaign";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";
import { formatDateTime } from "../utils/date";

const router = useRouter();
const auth = useAuthStore();

const campaigns = ref<CampaignResponse[]>([]);
const loading = ref(true);
const errorMessage = ref("");

const showForm = ref(false);
const formName = ref("");
const formObjective = ref("");
const formAudience = ref("");
const formChannels = ref<CampaignChannel[]>([]);
const formConstraints = ref("");
const formError = ref("");
const isSubmitting = ref(false);

const canCreate =
  auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN";

const allChannels: CampaignChannel[] = ["BLOG", "SOCIAL", "SHORT_VIDEO"];

function toggleChannel(channel: CampaignChannel) {
  const index = formChannels.value.indexOf(channel);
  if (index >= 0) {
    formChannels.value.splice(index, 1);
  } else {
    formChannels.value.push(channel);
  }
}

async function loadList() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const page = await listCampaigns(auth.accessToken!, 0, 20);
    campaigns.value = page.items;
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "加载失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: "/campaigns" }
      });
    }
  } finally {
    loading.value = false;
  }
}

function openForm() {
  showForm.value = true;
  formError.value = "";
}

function closeForm() {
  showForm.value = false;
  resetForm();
}

function resetForm() {
  formName.value = "";
  formObjective.value = "";
  formAudience.value = "";
  formChannels.value = [];
  formConstraints.value = "";
  formError.value = "";
}

async function submitForm() {
  if (isSubmitting.value) {
    return;
  }
  formError.value = "";

  if (!formName.value.trim()) {
    formError.value = "活动名称不能为空";
    return;
  }
  if (!formObjective.value.trim()) {
    formError.value = "活动目标不能为空";
    return;
  }
  if (!formAudience.value.trim()) {
    formError.value = "目标受众不能为空";
    return;
  }
  if (formChannels.value.length === 0) {
    formError.value = "campaign must target at least one channel";
    return;
  }

  const constraints = formConstraints.value
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  isSubmitting.value = true;
  try {
    await createCampaign(auth.accessToken!, {
      name: formName.value.trim(),
      objective: formObjective.value.trim(),
      audience: formAudience.value.trim(),
      channels: [...formChannels.value],
      constraints
    });
    closeForm();
    await loadList();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: "/campaigns" }
      });
      return;
    }
    formError.value =
      error instanceof ApiError ? error.message : "创建失败，请稍后重试";
  } finally {
    isSubmitting.value = false;
  }
}

onMounted(loadList);
</script>

<template>
  <AppShell active-route="campaigns">
    <div class="campaign-list-view">
      <div class="campaign-list-header">
        <div>
          <p class="eyebrow">CAMPAIGNS</p>
          <h1>内容活动</h1>
          <p>管理你的内容营销活动与 Brief。</p>
        </div>
        <button
          v-if="canCreate"
          class="primary-button"
          type="button"
          @click="openForm"
        >
          创建 Campaign
        </button>
      </div>

      <div v-if="showForm" class="campaign-form-card">
        <h2>新建 Campaign</h2>
        <form @submit.prevent="submitForm">
          <label for="campaign-name">活动名称</label>
          <input
            id="campaign-name"
            v-model="formName"
            data-testid="form-name"
            type="text"
            maxlength="128"
            placeholder="例如：PulseInk 秋招发布"
          />

          <label for="campaign-objective">目标</label>
          <textarea
            id="campaign-objective"
            v-model="formObjective"
            data-testid="form-objective"
            maxlength="4000"
            placeholder="向目标受众传达的核心目标"
          />

          <label for="campaign-audience">受众</label>
          <input
            id="campaign-audience"
            v-model="formAudience"
            data-testid="form-audience"
            type="text"
            maxlength="2000"
            placeholder="目标受众描述"
          />

          <label>渠道</label>
          <div class="channel-options" data-testid="form-channels">
            <label
              v-for="channel in allChannels"
              :key="channel"
              class="channel-option"
            >
              <input
                type="checkbox"
                :checked="formChannels.includes(channel)"
                @change="toggleChannel(channel)"
              />
              <span>{{ channel }}</span>
            </label>
          </div>

          <label for="campaign-constraints">约束（每行一条）</label>
          <textarea
            id="campaign-constraints"
            v-model="formConstraints"
            data-testid="form-constraints"
            placeholder="事实性结论必须给出引用&#10;避免夸大效果"
          />

          <p v-if="formError" class="form-error" role="alert" data-testid="form-error">
            {{ formError }}
          </p>

          <div class="prompt-actions">
            <button
              class="secondary-button"
              type="button"
              @click="closeForm"
            >
              取消
            </button>
            <button
              class="primary-button"
              type="submit"
              :disabled="isSubmitting"
              data-testid="form-submit"
            >
              {{ isSubmitting ? "正在创建…" : "创建" }}
            </button>
          </div>
        </form>
      </div>

      <div v-if="loading" class="campaign-loading" data-testid="loading">
        正在加载…
      </div>
      <div v-else-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </div>
      <div v-else-if="campaigns.length === 0" class="campaign-empty" data-testid="empty">
        暂无 Campaign，创建第一个活动开始吧。
      </div>
      <ul v-else class="campaign-list" data-testid="campaign-list">
        <li v-for="item in campaigns" :key="item.id">
          <RouterLink :to="`/campaigns/${item.id}`" class="campaign-item">
            <div class="campaign-item-main">
              <strong>{{ item.name }}</strong>
              <span class="campaign-status">{{ item.status }}</span>
            </div>
            <div class="campaign-item-meta">
              <span>{{ item.audience }}</span>
              <span>{{ item.channels.join(", ") }}</span>
              <span>{{ formatDateTime(item.createdAt) }}</span>
            </div>
          </RouterLink>
        </li>
      </ul>
    </div>
  </AppShell>
</template>
