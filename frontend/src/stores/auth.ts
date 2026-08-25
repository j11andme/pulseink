import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { requestJson } from "../api/http";

export interface AuthenticatedUser {
  id: number;
  username: string;
  role: string;
}

export interface AuthSession {
  accessToken: string;
  expiresIn: number;
  user: AuthenticatedUser;
}

export const useAuthStore = defineStore("auth", () => {
  const accessToken = ref<string>();
  const expiresAt = ref<number>();
  const user = ref<AuthenticatedUser>();
  const isAuthenticated = computed(() => Boolean(accessToken.value));

  function acceptSession(session: AuthSession) {
    accessToken.value = session.accessToken;
    expiresAt.value = Date.now() + session.expiresIn * 1000;
    user.value = session.user;
  }

  async function login(username: string, password: string) {
    const session = await requestJson<AuthSession>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
    acceptSession(session);
  }

  function logout() {
    accessToken.value = undefined;
    expiresAt.value = undefined;
    user.value = undefined;
  }

  return {
    accessToken,
    expiresAt,
    user,
    isAuthenticated,
    acceptSession,
    login,
    logout
  };
});
