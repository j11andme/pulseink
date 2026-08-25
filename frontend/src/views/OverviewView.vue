<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { listCampaigns, type CampaignResponse } from "../api/campaign";
import { ApiError } from "../api/http";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";
import { formatDateTime } from "../utils/date";

const router = useRouter();
const auth = useAuthStore();

const campaigns = ref<CampaignResponse[]>([]);
const totalElements = ref(0);
const loading = ref(true);
const errorMessage = ref("");

const canCreate =
  auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN";
const latestActivity = computed(() => campaigns.value[0] ?? null);

async function loadOverview() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const page = await listCampaigns(auth.accessToken!, 0, 20);
    campaigns.value = page.items;
    totalElements.value = page.totalElements;
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "加载失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: "/overview" }
      });
    }
  } finally {
    loading.value = false;
  }
}

onMounted(loadOverview);
</script>

<template>
  <AppShell active-route="overview">
    <div class="overview-view">
      <div class="overview-hero">
        <div>
          <p class="eyebrow">OVERVIEW</p>
          <h1>内容策划与运营工作台</h1>
          <p class="overview-copy">
            从一个 Campaign Brief 开始，完成 Agent 执行、人工审批、Sandbox 发布、
            Kafka 反馈回流与长期记忆洞察的完整闭环。
          </p>
        </div>
        <RouterLink
          v-if="canCreate"
          to="/campaigns"
          class="primary-button"
          data-testid="create-campaign-link"
        >
          创建 Campaign
        </RouterLink>
      </div>

      <div class="overview-grid">
        <section class="overview-card">
          <h2>Campaign 概览</h2>
          <div v-if="loading" class="overview-state" data-testid="overview-loading">
            正在加载…
          </div>
          <div
            v-else-if="errorMessage"
            class="overview-state overview-error"
            data-testid="overview-error"
          >
            <p>{{ errorMessage }}</p>
            <button
              class="secondary-button"
              type="button"
              data-testid="overview-retry"
              @click="loadOverview"
            >
              重试
            </button>
          </div>
          <div v-else class="overview-stats">
            <div class="stat-block">
              <dt>Campaign 总数</dt>
              <dd data-testid="campaign-total">{{ totalElements }}</dd>
            </div>
            <div class="stat-block">
              <dt>最近活动</dt>
              <dd data-testid="latest-activity">
                {{ latestActivity ? latestActivity.name : "暂无 Campaign" }}
              </dd>
              <span v-if="latestActivity">
                {{ formatDateTime(latestActivity.createdAt) }}
              </span>
            </div>
          </div>
        </section>

        <section class="overview-card">
          <h2>Golden Path</h2>
          <ol class="golden-path">
            <li><strong>Brief</strong><span>结构化活动目标、受众、渠道与约束</span></li>
            <li><strong>Agent</strong><span>AUTO 路由到 DIRECT / REACT / ORCHESTRATED</span></li>
            <li><strong>Review</strong><span>确定性校验 + 语义 Reviewer 与局部 Repair</span></li>
            <li><strong>Publish</strong><span>人工审批后幂等发布到 Channel Sandbox</span></li>
            <li><strong>Feedback</strong><span>Kafka 回流曝光、点击、点赞等指标</span></li>
            <li><strong>Memory</strong><span>候选洞察经人工批准后进入长期记忆</span></li>
          </ol>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.overview-view {
  display: grid;
  gap: 1.5rem;
}

.overview-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1.5rem;
}

.overview-hero h1 {
  margin: 0;
  color: #172033;
  font-size: 2rem;
  letter-spacing: -0.035em;
}

.overview-copy {
  max-width: 52rem;
  margin: 0.75rem 0 0;
  color: #596277;
  line-height: 1.8;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 1.25rem;
}

.overview-card {
  padding: 1.5rem;
  border: 1px solid #e4e8f0;
  border-radius: 1.1rem;
  background: #fff;
  box-shadow: 0 1rem 3rem rgba(28, 37, 60, 0.06);
}

.overview-card h2 {
  margin: 0 0 1rem;
  color: #172033;
  font-size: 1.15rem;
}

.overview-state {
  padding: 1.5rem;
  border-radius: 0.75rem;
  color: #596277;
  background: #f7f8fb;
  text-align: center;
}

.overview-error {
  display: grid;
  justify-items: center;
  gap: 0.75rem;
  color: #a52e2e;
  background: #fff5f5;
}

.overview-stats {
  display: grid;
  gap: 0.75rem;
}

.stat-block {
  padding: 0.85rem 1rem;
  border-radius: 0.7rem;
  background: #f7f8fb;
}

.stat-block dt {
  color: #667085;
  font-size: 0.75rem;
  font-weight: 700;
}

.stat-block dd {
  margin: 0.3rem 0 0;
  color: #172033;
  font-size: 1.2rem;
  font-weight: 800;
}

.stat-block span {
  display: block;
  margin-top: 0.25rem;
  color: #667085;
  font-size: 0.78rem;
}

.golden-path {
  display: grid;
  gap: 0.6rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.golden-path li {
  display: flex;
  align-items: baseline;
  gap: 0.7rem;
  padding: 0.65rem 0.8rem;
  border-radius: 0.65rem;
  background: #f7f8fb;
}

.golden-path strong {
  flex: 0 0 5.5rem;
  color: #5c5ce6;
  font-size: 0.85rem;
}

.golden-path span {
  color: #596277;
  font-size: 0.82rem;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .overview-hero {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
