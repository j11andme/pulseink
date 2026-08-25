<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { getIntegrations, type IntegrationItem, type IntegrationTool } from "../api/integration";
import { ApiError } from "../api/http";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";
import IntegrationCard from "../components/integration/IntegrationCard.vue";

const router = useRouter();
const auth = useAuthStore();

const integrations = ref<IntegrationItem[]>([]);
const tools = ref<IntegrationTool[]>([]);
const loading = ref(true);
const errorMessage = ref("");

const categoryOrder = ["MODEL", "RETRIEVAL", "RUNTIME", "MESSAGING", "PUBLISHING", "TOOLS"];
const grouped = computed(() => {
  const result = new Map<string, IntegrationItem[]>();
  for (const category of categoryOrder) {
    const matches = integrations.value.filter((item) => item.category === category);
    if (matches.length > 0) {
      result.set(category, matches);
    }
  }
  for (const item of integrations.value) {
    if (!result.has(item.category)) {
      result.set(item.category, [item]);
    }
  }
  return result;
});

async function loadIntegrations() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const response = await getIntegrations(auth.accessToken!);
    integrations.value = response.integrations;
    tools.value = response.tools;
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "加载失败，请稍后重试";
    if (error instanceof ApiError && error.status === 401) {
      auth.logout();
      await router.push({
        path: "/login",
        query: { redirect: "/integrations" }
      });
    }
  } finally {
    loading.value = false;
  }
}

onMounted(loadIntegrations);
</script>

<template>
  <AppShell active-route="integrations">
    <div class="integrations-view">
      <div class="integrations-header">
        <div>
          <p class="eyebrow">INTEGRATIONS</p>
          <h1>Integrations</h1>
          <p>
            展示后端各能力的配置/能力状态。本页只读，实际配置由根环境文件与后端管理；
            不会显示任何 API Key、密码、Token 或完整内部地址。
          </p>
        </div>
        <button
          class="secondary-button"
          type="button"
          data-testid="integrations-reload"
          @click="loadIntegrations"
        >
          刷新
        </button>
      </div>

      <div v-if="loading" class="view-state" data-testid="integrations-loading">
        正在加载…
      </div>
      <div v-else-if="errorMessage" class="view-state view-error" data-testid="integrations-error">
        <p>{{ errorMessage }}</p>
        <button class="secondary-button" type="button" @click="loadIntegrations">
          重试
        </button>
      </div>
      <div
        v-else-if="integrations.length === 0"
        class="view-state"
        data-testid="integrations-empty"
      >
        暂无 Integration 状态。
      </div>
      <template v-else>
        <section v-for="([category, items]) in grouped" :key="category" class="category-section">
          <h2>{{ category }}</h2>
          <div class="integration-grid">
            <IntegrationCard
              v-for="integration in items"
              :key="integration.id"
              :integration="{
                id: integration.id,
                displayName: integration.displayName,
                category: integration.category,
                status: integration.status,
                summary: integration.summary,
                capabilities: integration.capabilities
              }"
            />
          </div>
        </section>

        <section class="category-section">
          <h2>Tool Registry 工具定义</h2>
          <p class="section-note">只读元数据快照：名称、风险等级与公开描述。</p>
          <div v-if="tools.length === 0" class="view-state">暂无工具定义。</div>
          <ul v-else class="tool-list">
            <li v-for="tool in tools" :key="tool.qualifiedName">
              <div>
                <strong>{{ tool.qualifiedName }}</strong>
                <span>{{ tool.description }}</span>
              </div>
              <span class="risk-badge" :class="{ 'risk-write': tool.risk !== 'READ' }">
                {{ tool.risk }}
              </span>
            </li>
          </ul>
        </section>
      </template>
    </div>
  </AppShell>
</template>

<style scoped>
.integrations-view {
  display: grid;
  gap: 1.5rem;
}

.integrations-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
}

.integrations-header h1 {
  margin: 0;
  color: #172033;
  font-size: 2rem;
  letter-spacing: -0.035em;
}

.integrations-header p:not(.eyebrow) {
  max-width: 52rem;
  margin: 0.75rem 0 0;
  color: #596277;
  line-height: 1.8;
}

.view-state {
  padding: 2rem;
  border: 1px solid #e4e8f0;
  border-radius: 1rem;
  color: #596277;
  background: #fff;
  text-align: center;
}

.view-error {
  display: grid;
  justify-items: center;
  gap: 0.75rem;
  color: #a52e2e;
  background: #fff5f5;
}

.category-section {
  display: grid;
  gap: 0.75rem;
}

.category-section h2 {
  margin: 0;
  color: #172033;
  font-size: 1.05rem;
}

.section-note {
  margin: 0;
  color: #667085;
  font-size: 0.8rem;
}

.integration-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr));
  gap: 0.85rem;
}

.tool-list {
  display: grid;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.tool-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.8rem 1rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.75rem;
  background: #fff;
}

.tool-list li div {
  display: grid;
  gap: 0.2rem;
}

.tool-list strong {
  color: #172033;
  font-size: 0.88rem;
}

.tool-list span {
  color: #667085;
  font-size: 0.8rem;
}

.risk-badge {
  flex: 0 0 auto;
  padding: 0.25rem 0.55rem;
  border-radius: 0.45rem;
  color: #166b4b;
  background: #e8f7f0;
  font-size: 0.72rem;
  font-weight: 800;
}

.risk-write {
  color: #7a3c12;
  background: #fdf1e5;
}
</style>
