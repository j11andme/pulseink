<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getCampaign, type CampaignResponse } from "../api/campaign";
import { ApiError } from "../api/http";
import type { RunResponse } from "../api/run";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";
import BriefPanel from "../components/campaign/BriefPanel.vue";
import RunSetupPanel from "../components/run/RunSetupPanel.vue";
import RunWorkspace from "../components/run/RunWorkspace.vue";
import ContentWorkflowPanel from "../components/content/ContentWorkflowPanel.vue";
import PublishPanel from "../components/publish/PublishPanel.vue";
import MetricsPanel from "../components/metrics/MetricsPanel.vue";
import InsightPanel from "../components/memory/InsightPanel.vue";
import { formatDateTime } from "../utils/date";

type StudioTab = "brief" | "run" | "content" | "publish" | "metrics" | "insights";

const STUDIO_TABS: StudioTab[] = ["brief", "run", "content", "publish", "metrics", "insights"];
const TAB_LABELS: Record<StudioTab, string> = {
  brief: "Brief",
  run: "Run",
  content: "Content",
  publish: "Publish",
  metrics: "Metrics",
  insights: "Insights"
};

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const rawCampaignId = computed(() => route.params.campaignId as string | undefined);
const campaignId = computed(() => {
  const value = Number(rawCampaignId.value);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
});

const campaign = ref<CampaignResponse | null>(null);
const loading = ref(false);
const errorStatus = ref<number | null>(null);
const errorMessage = ref("");

const activeTab = ref<StudioTab>("brief");
const selectedRunId = ref<number | null>(null);
const notice = ref("");

function parseQueryRunId(): number | null {
  const value = route.query.runId;
  if (typeof value !== "string" || !/^\d+$/.test(value)) {
    return null;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function parseQueryTab(): StudioTab {
  const value = route.query.tab;
  return typeof value === "string" &&
    (STUDIO_TABS as string[]).includes(value)
    ? (value as StudioTab)
    : "brief";
}

function syncQuery() {
  const query: Record<string, string> = { tab: activeTab.value };
  if (selectedRunId.value !== null && selectedRunId.value > 0) {
    query.runId = String(selectedRunId.value);
  }
  void router.replace({ query });
}

async function loadDetail() {
  if (campaignId.value === null) {
    return;
  }
  loading.value = true;
  errorStatus.value = null;
  errorMessage.value = "";
  try {
    campaign.value = await getCampaign(auth.accessToken!, campaignId.value);
  } catch (error) {
    if (error instanceof ApiError) {
      errorStatus.value = error.status;
      errorMessage.value = error.message;
      if (error.status === 401) {
        auth.logout();
        await router.push({
          path: "/login",
          query: { redirect: route.fullPath }
        });
      }
    } else {
      errorStatus.value = 500;
      errorMessage.value = "加载失败，请稍后重试";
    }
  } finally {
    loading.value = false;
  }
}

function initializeQuery() {
  const requestedTab = parseQueryTab();
  const requestedRunId = parseQueryRunId();
  const rawTab = route.query.tab;
  const tabProvided = rawTab !== undefined && rawTab !== null;
  const tabInvalid = tabProvided && typeof rawTab === "string" &&
    !(STUDIO_TABS as string[]).includes(rawTab);
  if (tabInvalid) {
    notice.value = `未知 Tab（${String(rawTab)}），已回退到 Brief。`;
    activeTab.value = "brief";
    selectedRunId.value = null;
    void router.replace({ query: { tab: "brief" } });
    return;
  }
  activeTab.value = requestedTab;
  selectedRunId.value = requestedRunId;
  if (requestedTab === "run") {
    if (requestedRunId === null) {
      notice.value = "URL 中的 runId 无效，已回退到最新 Run。";
    }
  }
}

function selectTab(tab: StudioTab) {
  if (tab === activeTab.value) {
    return;
  }
  activeTab.value = tab;
  notice.value = "";
  syncQuery();
}

function onRunCreated(run: RunResponse) {
  selectedRunId.value = run.runId;
  notice.value = "";
  syncQuery();
}

function onWorkspaceRunId(runId: number) {
  if (runId !== selectedRunId.value) {
    notice.value = "已回退到最新 Run。";
  }
  selectedRunId.value = runId;
  syncQuery();
}

onMounted(() => {
  initializeQuery();
  void loadDetail();
});
</script>

<template>
  <AppShell active-route="campaigns">
    <div class="studio-view">
      <div v-if="campaignId === null" class="studio-error" data-testid="studio-invalid-id">
        <p>无效的 Campaign ID。</p>
        <RouterLink to="/campaigns" class="secondary-button">返回列表</RouterLink>
      </div>
      <div v-else-if="loading" class="studio-state" data-testid="studio-loading">
        正在加载…
      </div>
      <div v-else-if="errorStatus === 404" class="studio-error" data-testid="studio-not-found">
        <p>Campaign 不存在</p>
        <RouterLink to="/campaigns" class="secondary-button">返回列表</RouterLink>
      </div>
      <div v-else-if="errorStatus" class="studio-error" data-testid="studio-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadDetail">重试</button>
      </div>
      <div v-else-if="campaign" class="studio-content" data-testid="studio-content">
        <header class="studio-header">
          <div>
            <p class="eyebrow">CAMPAIGN #{{ campaign.id }}</p>
            <h1>{{ campaign.name }}</h1>
            <p class="studio-meta">
              {{ campaign.status }} · {{ campaign.channels.join(", ") }} ·
              {{ formatDateTime(campaign.updatedAt) }}
            </p>
          </div>
          <RouterLink to="/campaigns" class="secondary-button">返回列表</RouterLink>
        </header>

        <p v-if="notice" class="studio-notice" role="status">{{ notice }}</p>

        <nav class="studio-tabs" aria-label="Campaign Studio Tabs">
          <button
            v-for="tab in STUDIO_TABS"
            :key="tab"
            type="button"
            class="studio-tab"
            :class="{ 'studio-tab-active': activeTab === tab }"
            :data-testid="`studio-tab-${tab}`"
            @click="selectTab(tab)"
          >
            {{ TAB_LABELS[tab] }}
          </button>
        </nav>

        <div class="studio-tab-content">
          <BriefPanel v-if="activeTab === 'brief'" :campaign="campaign" />

          <template v-else-if="activeTab === 'run'">
            <RunSetupPanel :campaign="campaign" @run-created="onRunCreated" />
            <RunWorkspace
              :campaign="campaign"
              :selected-run-id="selectedRunId"
              @update:run-id="onWorkspaceRunId"
            />
          </template>

          <ContentWorkflowPanel v-else-if="activeTab === 'content'" :run-id="selectedRunId" />
          <PublishPanel
            v-else-if="activeTab === 'publish'"
            :run-id="selectedRunId"
            :campaign-channels="campaign.channels"
            @return-to-editing="selectTab('content')"
          />
          <MetricsPanel v-else-if="activeTab === 'metrics'" :run-id="selectedRunId" />
          <InsightPanel
            v-else-if="activeTab === 'insights'"
            :run-id="selectedRunId"
            :campaign-id="campaign.id"
          />
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.studio-view {
  display: grid;
  gap: 1.5rem;
}

.studio-state,
.studio-error {
  display: grid;
  justify-items: start;
  gap: 0.75rem;
  padding: 2rem;
  border: 1px solid #e4e8f0;
  border-radius: 1rem;
  color: #3d465a;
  background: #fff;
}

.studio-content {
  display: grid;
  gap: 1.25rem;
}

.studio-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.studio-header h1 {
  margin: 0;
  color: #172033;
  font-size: 2rem;
  letter-spacing: -0.035em;
}

.studio-meta {
  margin: 0.5rem 0 0;
  color: #596277;
  font-size: 0.85rem;
}

.studio-notice {
  margin: 0;
  padding: 0.7rem 0.85rem;
  border-radius: 0.65rem;
  color: #7a3c12;
  background: #fdf1e5;
  font-size: 0.84rem;
}

.studio-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0.5rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.8rem;
  background: #fff;
}

.studio-tab {
  padding: 0.55rem 0.9rem;
  border: 0;
  border-radius: 0.6rem;
  color: #596277;
  background: transparent;
  font-weight: 700;
}

.studio-tab-active {
  color: #5c5ce6;
  background: #f0f0ff;
}

.studio-tab-content {
  display: grid;
  gap: 1rem;
}
</style>
