<script setup lang="ts">
import type { CampaignResponse } from "../../api/campaign";
import { formatDateTime } from "../../utils/date";

defineProps<{
  campaign: CampaignResponse;
}>();
</script>

<template>
  <section class="brief-panel">
    <dl class="brief-meta">
      <div>
        <dt>状态</dt>
        <dd>{{ campaign.status }}</dd>
      </div>
      <div>
        <dt>渠道</dt>
        <dd>{{ campaign.channels.join(", ") }}</dd>
      </div>
      <div>
        <dt>创建时间</dt>
        <dd>{{ formatDateTime(campaign.createdAt) }}</dd>
      </div>
      <div>
        <dt>更新时间</dt>
        <dd>{{ formatDateTime(campaign.updatedAt) }}</dd>
      </div>
    </dl>

    <div class="brief-section">
      <h3>目标</h3>
      <p>{{ campaign.objective }}</p>
    </div>
    <div class="brief-section">
      <h3>受众</h3>
      <p>{{ campaign.audience }}</p>
    </div>
    <div v-if="campaign.constraints.length > 0" class="brief-section">
      <h3>约束</h3>
      <ul>
        <li v-for="constraint in campaign.constraints" :key="constraint">
          {{ constraint }}
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.brief-panel {
  display: grid;
  gap: 1rem;
  padding: 1.25rem;
  border: 1px solid #e4e8f0;
  border-radius: 1rem;
  background: #fff;
}

.brief-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 0.6rem;
  margin: 0;
}

.brief-meta div {
  padding: 0.65rem 0.8rem;
  border-radius: 0.65rem;
  background: #f7f8fb;
}

.brief-meta dt,
.brief-section h3 {
  color: #667085;
  font-size: 0.72rem;
  font-weight: 800;
}

.brief-meta dd {
  margin: 0.25rem 0 0;
  color: #172033;
  font-size: 0.88rem;
  font-weight: 700;
}

.brief-section h3 {
  margin: 0 0 0.45rem;
  color: #172033;
  font-size: 0.9rem;
}

.brief-section p {
  margin: 0;
  color: #3d465a;
  line-height: 1.8;
}

.brief-section ul {
  margin: 0;
  padding-left: 1.2rem;
  color: #3d465a;
  line-height: 1.8;
}
</style>
