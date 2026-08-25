<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../api/http";
import { useAuthStore } from "../stores/auth";

const router = useRouter();
const auth = useAuthStore();
const username = ref("demo");
const password = ref("");
const isSubmitting = ref(false);
const errorMessage = ref("");

async function submit() {
  if (isSubmitting.value) {
    return;
  }

  errorMessage.value = "";
  isSubmitting.value = true;
  try {
    await auth.login(username.value.trim(), password.value);
    const redirect = router.currentRoute.value.query.redirect;
    if (typeof redirect === "string" && redirect.startsWith("/") && !redirect.startsWith("//")) {
      await router.push(redirect);
    } else {
      await router.push("/campaigns");
    }
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : "暂时无法登录，请稍后重试";
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="brand-panel" aria-labelledby="brand-title">
      <div class="brand-mark" aria-hidden="true">P</div>
      <p class="eyebrow">AI CAMPAIGN STUDIO</p>
      <h1 id="brand-title">让内容策略从想法走向可执行方案</h1>
      <p class="brand-copy">
        PulseInk 将检索、策划、创作与审核组织成可观察的 Agent 工作流。
      </p>
      <ul class="feature-list">
        <li>按任务选择 Direct、ReAct 或多 Agent 协作</li>
        <li>统一接入模型、知识库与工具</li>
        <li>保留证据、过程与人工审批边界</li>
      </ul>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-card">
        <p class="eyebrow">WELCOME BACK</p>
        <h2 id="login-title">登录 PulseInk</h2>
        <p class="login-intro">进入内容活动工作台，开始一次完整的 Agent 执行。</p>

        <form @submit.prevent="submit">
          <label for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            data-testid="username"
            name="username"
            autocomplete="username"
            required
          />

          <label for="password">密码</label>
          <input
            id="password"
            v-model="password"
            data-testid="password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="输入演示密码"
            required
          />

          <p v-if="errorMessage" class="form-error" role="alert">
            {{ errorMessage }}
          </p>

          <button type="submit" :disabled="isSubmitting">
            {{ isSubmitting ? "正在登录…" : "进入工作台" }}
          </button>
        </form>

        <p class="demo-hint">
          本地演示账号 <code>demo</code>，密码由
          <code>PULSEINK_DEMO_PASSWORD</code> 配置。
        </p>
      </div>
    </section>
  </main>
</template>
