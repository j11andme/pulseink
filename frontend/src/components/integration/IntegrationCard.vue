<script setup lang="ts">
import { computed } from "vue";
import type { IntegrationItem } from "../../api/integration";

const props = defineProps<{
  integration: IntegrationItem;
}>();

const capabilities = computed(() => props.integration.capabilities ?? []);

const statusText: Record<string, string> = {
  CONFIGURED: "已配置",
  DISABLED: "已禁用"
};

const categoryText: Record<string, string> = {
  MODEL: "模型",
  RETRIEVAL: "检索",
  RUNTIME: "运行时",
  MESSAGING: "消息",
  PUBLISHING: "发布",
  TOOLS: "工具"
};
</script>

<template>
  <article class="integration-card">
    <div class="integration-card-header">
      <div>
        <p class="integration-category">
          {{ integration.category }} · {{ categoryText[integration.category] ?? integration.category }}
        </p>
        <h3>{{ integration.displayName }}</h3>
      </div>
      <span
        class="integration-status"
        data-testid="integration-status"
        :class="integration.status === 'CONFIGURED' ? 'is-configured' : 'is-disabled'"
      >
        {{ statusText[integration.status] ?? integration.status }}
      </span>
    </div>
    <p class="integration-summary">{{ integration.summary }}</p>
    <ul v-if="capabilities.length > 0" class="integration-capabilities">
      <li v-for="capability in capabilities" :key="capability">
        {{ capability }}
      </li>
    </ul>
  </article>
</template>

<style scoped>
.integration-card {
  padding: 1.1rem 1.2rem;
  border: 1px solid #e4e8f0;
  border-radius: 0.9rem;
  background: #fff;
}

.integration-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.integration-category {
  margin: 0;
  color: #5c5ce6;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.integration-card h3 {
  margin: 0.25rem 0 0;
  color: #172033;
  font-size: 1rem;
}

.integration-status {
  flex: 0 0 auto;
  padding: 0.25rem 0.6rem;
  border-radius: 0.5rem;
  font-size: 0.75rem;
  font-weight: 800;
}

.is-configured {
  color: #166b4b;
  background: #e8f7f0;
}

.is-disabled {
  color: #7a3c12;
  background: #fdf1e5;
}

.integration-summary {
  margin: 0.7rem 0 0;
  color: #596277;
  font-size: 0.85rem;
  line-height: 1.7;
}

.integration-capabilities {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin: 0.8rem 0 0;
  padding: 0;
  list-style: none;
}

.integration-capabilities li {
  padding: 0.25rem 0.55rem;
  border-radius: 0.45rem;
  color: #4a3f9c;
  background: #f0f0ff;
  font-size: 0.75rem;
}
</style>
