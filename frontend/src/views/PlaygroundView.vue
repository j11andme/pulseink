<script setup lang="ts">
import { computed, ref } from "vue";
import { useModelStream } from "../composables/useModelStream";
import { useAuthStore } from "../stores/auth";
import AppShell from "../components/layout/AppShell.vue";

const stream = useModelStream();
const auth = useAuthStore();
const message = ref("");
const temperature = ref(0.3);
const maxTokens = ref(4096);

const statusLabel = computed(() => {
  switch (stream.status.value) {
    case "connecting":
      return "正在连接";
    case "streaming":
      return "生成中";
    case "completed":
      return "已完成";
    case "failed":
      return "生成失败";
    default:
      return "等待输入";
  }
});

async function submit() {
  if (!message.value.trim() || stream.isActive.value) {
    return;
  }
  await stream.start(
    message.value,
    temperature.value,
    maxTokens.value
  );
}
</script>

<template>
  <AppShell active-route="playground">
    <div class="workspace-intro">
      <div>
        <p class="eyebrow">MODEL PLAYGROUND</p>
        <h1>模型体验台</h1>
        <p>
          输入一个内容任务，查看 PulseInk 通过统一模型端口返回的实时结果。
          本地默认使用 Fake Provider，无需配置密钥也能验证完整链路。
        </p>
      </div>
      <div class="stream-summary">
        <span
          class="status-dot"
          :class="`status-${stream.status.value}`"
          aria-hidden="true"
        />
        <div>
          <small>当前状态</small>
          <strong data-testid="stream-status">{{ statusLabel }}</strong>
        </div>
      </div>
    </div>

    <div class="playground-grid">
      <form
        class="prompt-card"
        data-testid="model-form"
        @submit.prevent="submit"
      >
        <div class="card-heading">
          <div>
            <span>INPUT</span>
            <h2>描述你的内容任务</h2>
          </div>
          <span class="step-badge">01</span>
        </div>

        <label for="model-message">任务描述</label>
        <textarea
          id="model-message"
          v-model="message"
          data-testid="model-message"
          maxlength="8000"
          placeholder="例如：为 Java 后端开发者写一段 PulseInk 的产品介绍，突出多 Agent 协作与可迁移性。"
        />
        <div class="input-meta">
          <span>{{ message.length }} / 8000</span>
          <span>不会在此阶段持久化对话正文</span>
        </div>

        <div class="model-options">
          <label>
            <span>Temperature</span>
            <input
              v-model.number="temperature"
              type="number"
              min="0"
              max="2"
              step="0.1"
            />
          </label>
          <label>
            <span>Max tokens</span>
            <input
              v-model.number="maxTokens"
              type="number"
              min="1"
              max="8192"
              step="1"
            />
          </label>
        </div>

        <div class="prompt-actions">
          <button
            v-if="stream.isActive.value"
            class="secondary-button stop-button"
            type="button"
            @click="stream.stop"
          >
            停止生成
          </button>
          <button
            class="primary-button"
            type="submit"
            :disabled="!message.trim() || stream.isActive.value"
          >
            {{ stream.isActive.value ? "正在生成…" : "开始生成" }}
          </button>
        </div>
      </form>

      <section class="output-card" aria-live="polite">
        <div class="card-heading">
          <div>
            <span>OUTPUT</span>
            <h2>实时模型结果</h2>
          </div>
          <span class="step-badge">02</span>
        </div>

        <div
          class="model-output"
          data-testid="model-output"
          :class="{ 'model-output-empty': !stream.content.value }"
        >
          {{
            stream.content.value ||
            "模型输出会随着 content_delta 事件逐步出现在这里。"
          }}
        </div>

        <p
          v-if="stream.errorMessage.value"
          class="form-error output-error"
          role="alert"
        >
          {{ stream.errorMessage.value }}
        </p>

        <dl class="stream-metadata">
          <div>
            <dt>Provider</dt>
            <dd>{{ stream.provider.value || "-" }}</dd>
          </div>
          <div>
            <dt>Model</dt>
            <dd>{{ stream.model.value || "-" }}</dd>
          </div>
          <div>
            <dt>Request ID</dt>
            <dd>{{ stream.requestId.value || "-" }}</dd>
          </div>
        </dl>
      </section>
    </div>
  </AppShell>
</template>
