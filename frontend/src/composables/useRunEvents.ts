import { ref, watch, type Ref } from "vue";
import { useRouter } from "vue-router";
import { ApiError } from "../api/http";
import { useAuthStore } from "../stores/auth";

export interface RunEventCursor {
  runId: number;
  lastEventId?: string;
}

export interface RunEventEnvelope {
  eventVersion: "run-event-v1";
  runId: number;
  sequence: number;
  eventType: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export type RunStreamStatus =
  | "idle"
  | "connecting"
  | "streaming"
  | "reconnecting"
  | "completed"
  | "waiting"
  | "failed";

const RECONNECT_DELAYS_MS = [1000, 2000, 5000];
const COMPLETED_RUN_STATES = new Set(["COMPLETED"]);
const FAILED_RUN_STATES = new Set(["FAILED", "CANCELLED"]);
const WAITING_RUN_STATES = new Set(["WAITING_APPROVAL", "WAITING_HUMAN"]);

function sessionKey(runId: number): string {
  return `pulseink.run.${runId}.lastEventId`;
}

function readLastEventId(runId: number, cursorLastEventId?: string): string {
  return sessionStorage.getItem(sessionKey(runId)) ?? cursorLastEventId ?? "";
}

function persistLastEventId(runId: number, sequence: number): void {
  sessionStorage.setItem(sessionKey(runId), String(sequence));
}

export function useRunEvents(cursor: Ref<RunEventCursor>) {
  const auth = useAuthStore();
  const router = useRouter();
  const events = ref<RunEventEnvelope[]>([]);
  const status = ref<RunStreamStatus>("idle");
  const errorMessage = ref("");
  const seenSequences = new Set<number>();
  let activeController: AbortController | undefined;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let reconnectAttempts = 0;

  function clearReconnectTimer() {
    if (reconnectTimer !== undefined) {
      clearTimeout(reconnectTimer);
      reconnectTimer = undefined;
    }
  }

  function stop() {
    activeController?.abort();
    activeController = undefined;
    clearReconnectTimer();
    if (
      status.value === "connecting" ||
      status.value === "streaming" ||
      status.value === "reconnecting"
    ) {
      status.value = "idle";
    }
  }

  async function connect() {
    reconnectAttempts = 0;
    await open(cursor.value.runId);
  }

  function reconnect() {
    reconnectAttempts = 0;
    void open(cursor.value.runId);
  }

  async function open(runId: number) {
    activeController?.abort();
    activeController = undefined;
    clearReconnectTimer();
    if (!Number.isSafeInteger(runId) || runId <= 0) {
      status.value = "failed";
      errorMessage.value = "Run ID 无效，无法订阅事件流";
      return;
    }

    const controller = new AbortController();
    activeController = controller;
    status.value = "connecting";
    errorMessage.value = "";

    const lastEventId = readLastEventId(runId, cursor.value.lastEventId);
    try {
      const response = await fetch(`/api/runs/${runId}/events`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${auth.accessToken ?? ""}`,
          Accept: "text/event-stream",
          ...(lastEventId ? { "Last-Event-ID": lastEventId } : {})
        },
        signal: controller.signal
      });

      if (response.status === 401) {
        stop();
        status.value = "failed";
        errorMessage.value = "登录状态已失效，请重新登录";
        auth.logout();
        await router.push("/login");
        return;
      }
      if (!response.ok) {
        throw await toApiError(response);
      }
      if (!response.body) {
        throw new ApiError(502, "EMPTY_RUN_STREAM", "Run 事件服务没有返回数据流");
      }

      status.value = "streaming";
      await parseRunEventStream(response.body, handleEnvelope);

      if (activeController !== controller || status.value !== "streaming") {
        return;
      }
      scheduleReconnect(runId);
    } catch (error) {
      if (activeController !== controller || controller.signal.aborted) {
        return;
      }
      if (error instanceof ApiError && error.status === 401) {
        stop();
        status.value = "failed";
        errorMessage.value = "登录状态已失效，请重新登录";
        auth.logout();
        await router.push("/login");
        return;
      }
      scheduleReconnect(runId);
    }
  }

  function handleEnvelope(envelope: RunEventEnvelope) {
    if (
      envelope.eventVersion !== "run-event-v1" ||
      envelope.runId !== cursor.value.runId ||
      !Number.isSafeInteger(envelope.sequence) ||
      envelope.sequence <= 0 ||
      seenSequences.has(envelope.sequence)
    ) {
      return;
    }
    reconnectAttempts = 0;
    seenSequences.add(envelope.sequence);
    events.value = [...events.value, envelope].sort(
      (left, right) => left.sequence - right.sequence
    );
    persistLastEventId(envelope.runId, envelope.sequence);

    if (envelope.eventType === "RUN_STATE_CHANGED") {
      const toState = String(envelope.payload?.toState ?? "");
      if (COMPLETED_RUN_STATES.has(toState)) {
        status.value = "completed";
        activeController?.abort();
        clearReconnectTimer();
      } else if (FAILED_RUN_STATES.has(toState)) {
        status.value = "failed";
        errorMessage.value = `Run 已${toState === "CANCELLED" ? "取消" : "失败"}（${toState}）`;
        activeController?.abort();
        clearReconnectTimer();
      } else if (WAITING_RUN_STATES.has(toState)) {
        status.value = "waiting";
        activeController?.abort();
        clearReconnectTimer();
      }
    }
  }

  function scheduleReconnect(runId: number) {
    if (reconnectAttempts >= RECONNECT_DELAYS_MS.length) {
      status.value = "failed";
      errorMessage.value = "连续重连失败，已停止 Run 事件订阅";
      return;
    }
    const delay = RECONNECT_DELAYS_MS[reconnectAttempts];
    reconnectAttempts += 1;
    status.value = "reconnecting";
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined;
      void open(runId);
    }, delay);
  }

  watch(
    () => cursor.value.runId,
    (nextRunId, previousRunId) => {
      if (nextRunId === previousRunId) {
        return;
      }
      activeController?.abort();
      activeController = undefined;
      clearReconnectTimer();
      events.value = [];
      seenSequences.clear();
      status.value = "idle";
      errorMessage.value = "";
      if (Number.isSafeInteger(nextRunId) && nextRunId > 0) {
        void open(nextRunId);
      }
    }
  );

  return {
    events,
    status,
    errorMessage,
    connect,
    reconnect,
    stop
  };
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as {
      code?: string;
      message?: string;
    };
    return new ApiError(
      response.status,
      body.code ?? "RUN_STREAM_FAILED",
      body.message ?? "Run 事件请求失败"
    );
  } catch {
    return new ApiError(response.status, "RUN_STREAM_FAILED", "Run 事件请求失败");
  }
}

async function parseRunEventStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (envelope: RunEventEnvelope) => void
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let dataLines: string[] = [];

  const dispatch = () => {
    if (dataLines.length === 0) {
      return;
    }
    try {
      onEvent(JSON.parse(dataLines.join("\n")) as RunEventEnvelope);
    } catch {
      throw new ApiError(
        502,
        "INVALID_RUN_STREAM",
        "Run 事件服务返回了无法解析的事件"
      );
    } finally {
      dataLines = [];
    }
  };

  const consumeLine = (rawLine: string) => {
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    if (line === "") {
      dispatch();
      return;
    }
    if (line.startsWith(":")) {
      return;
    }
    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    let value = separator < 0 ? "" : line.slice(separator + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "data") {
      dataLines.push(value);
    }
  };

  const drainCompleteLines = () => {
    let lineBreak = buffer.indexOf("\n");
    while (lineBreak >= 0) {
      consumeLine(buffer.slice(0, lineBreak));
      buffer = buffer.slice(lineBreak + 1);
      lineBreak = buffer.indexOf("\n");
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      buffer += decoder.decode();
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    drainCompleteLines();
  }

  if (buffer) {
    consumeLine(buffer);
  }
  dispatch();
}
