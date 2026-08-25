import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../api/http";
import {
  streamModelChat,
  type ModelServerEvent
} from "../api/model";
import { useAuthStore } from "../stores/auth";

export type ModelStreamStatus =
  | "idle"
  | "connecting"
  | "streaming"
  | "completed"
  | "failed";

export function useModelStream() {
  const auth = useAuthStore();
  const router = useRouter();
  const status = ref<ModelStreamStatus>("idle");
  const content = ref("");
  const errorMessage = ref("");
  const requestId = ref("");
  const provider = ref("");
  const model = ref("");
  let activeController: AbortController | undefined;

  const isActive = computed(
    () => status.value === "connecting" || status.value === "streaming"
  );

  async function start(
    message: string,
    temperature?: number,
    maxTokens?: number
  ) {
    stop();
    const controller = new AbortController();
    activeController = controller;
    status.value = "connecting";
    content.value = "";
    errorMessage.value = "";
    requestId.value = "";
    provider.value = "";
    model.value = "";

    try {
      await streamModelChat(
        { message, temperature, maxTokens },
        {
          accessToken: auth.accessToken ?? "",
          signal: controller.signal,
          onEvent: handleEvent
        }
      );
      if (
        activeController === controller &&
        (status.value === "connecting" || status.value === "streaming")
      ) {
        status.value = "failed";
        errorMessage.value = "模型流意外结束，请重试";
      }
    } catch (error) {
      if (activeController !== controller || controller.signal.aborted) {
        return;
      }
      status.value = "failed";
      if (error instanceof ApiError && error.status === 401) {
        auth.logout();
        errorMessage.value = "登录状态已失效，请重新登录";
        await router.push("/login");
        return;
      }
      errorMessage.value =
        error instanceof Error ? error.message : "模型请求失败，请稍后重试";
    } finally {
      if (activeController === controller) {
        activeController = undefined;
      }
    }
  }

  function stop() {
    activeController?.abort();
    activeController = undefined;
    if (status.value === "connecting" || status.value === "streaming") {
      status.value = "idle";
    }
  }

  function handleEvent(event: ModelServerEvent) {
    switch (event.name) {
      case "started":
        requestId.value = event.data.requestId;
        provider.value = event.data.provider;
        model.value = event.data.model;
        status.value = "streaming";
        break;
      case "content_delta":
        content.value += event.data.content;
        status.value = "streaming";
        break;
      case "completed":
        status.value = "completed";
        break;
      case "error":
        status.value = "failed";
        errorMessage.value = event.data.message;
        break;
    }
  }

  return {
    status,
    content,
    errorMessage,
    requestId,
    provider,
    model,
    isActive,
    start,
    stop
  };
}
