<script setup lang="ts">
import { useRouter } from "vue-router";
import { useAuthStore } from "../../stores/auth";

defineProps<{
  activeRoute: "overview" | "campaigns" | "knowledge" | "integrations" | "playground" | "evaluation";
}>();

const router = useRouter();
const auth = useAuthStore();

async function logout() {
  auth.logout();
  await router.push("/login");
}
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-header">
      <div class="workspace-brand">
        <span class="brand-mark brand-mark-small" aria-hidden="true">P</span>
        <div>
          <strong>PulseInk</strong>
          <span>Campaign Studio</span>
        </div>
      </div>
      <nav class="shell-nav" data-testid="shell-nav" aria-label="主导航">
        <RouterLink
          to="/overview"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'overview' }"
        >
          Overview
        </RouterLink>
        <RouterLink
          to="/campaigns"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'campaigns' }"
        >
          Campaigns
        </RouterLink>
        <RouterLink
          to="/knowledge"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'knowledge' }"
        >
          Knowledge
        </RouterLink>
        <RouterLink
          to="/integrations"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'integrations' }"
        >
          Integrations
        </RouterLink>
        <RouterLink
          to="/evaluations"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'evaluation' }"
        >
          Evaluation Lab
        </RouterLink>
        <RouterLink
          to="/playground"
          class="shell-nav-link"
          :class="{ 'shell-nav-active': activeRoute === 'playground' }"
        >
          Model Playground
        </RouterLink>
      </nav>
      <div class="user-actions">
        <div class="user-identity">
          <strong>{{ auth.user?.username }}</strong>
          <span>{{ auth.user?.role }}</span>
        </div>
        <button
          class="secondary-button"
          data-testid="logout"
          type="button"
          @click="logout"
        >
          退出登录
        </button>
      </div>
    </header>

    <section class="workspace-content">
      <slot />
    </section>
  </main>
</template>
