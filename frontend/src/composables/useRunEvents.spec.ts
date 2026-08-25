import { createPinia, setActivePinia } from "pinia";
import { nextTick, ref } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../stores/auth";
import { useRunEvents } from "./useRunEvents";

const push = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({ push })
}));

function envelope(
  runId: number,
  sequence: number,
  eventType = "RUN_STATE_CHANGED",
  payload: Record<string, unknown> = { toState: "RUNNING" }
): Record<string, unknown> {
  return {
    eventVersion: "run-event-v1",
    runId,
    sequence,
    eventType,
    payload,
    createdAt: "2026-08-04T12:00:00Z"
  };
}

function sseLines(
  runId: number,
  events: Array<{ sequence: number; type: string; payload: Record<string, unknown> }>
): string {
  return events
    .map(
      (event) =>
        `event: ${event.type.toLowerCase()}\n` +
        `data: ${JSON.stringify(envelope(runId, event.sequence, event.type, event.payload))}\n\n`
    )
    .join("");
}

function chunkedStream(text: string, chunkSizes: number[]): ReadableStream<Uint8Array> {
  const bytes = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({
    start(controller) {
      let offset = 0;
      for (const size of chunkSizes) {
        controller.enqueue(bytes.slice(offset, offset + size));
        offset += size;
      }
      if (offset < bytes.length) {
        controller.enqueue(bytes.slice(offset));
      }
      controller.close();
    }
  });
}

function responseWithBody(body: ReadableStream<Uint8Array>, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ "Content-Type": "text/event-stream" }),
    body
  } as unknown as Response;
}

function pendingResponse() {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ "Content-Type": "text/event-stream" }),
    body: new ReadableStream<Uint8Array>({
      start() {
        // The stream stays open until the fetch signal aborts it.
      }
    })
  } as unknown as Response;
}

describe("useRunEvents", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    sessionStorage.clear();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("parses fragmented chunks and closes after a terminal run state", async () => {
    const cursor = ref({ runId: 7 });
    const text = sseLines(7, [
      { sequence: 1, type: "EXECUTION_MODE_SELECTED", payload: { selectedMode: "REACT" } },
      { sequence: 2, type: "DECISION_RECORDED", payload: { decisionSummary: "use react" } },
      { sequence: 3, type: "RUN_STATE_CHANGED", payload: { toState: "WAITING_APPROVAL" } }
    ]);
    const fetchMock = vi.fn().mockResolvedValue(
      responseWithBody(chunkedStream(text, [3, 17, 1, 60]))
    );
    vi.stubGlobal("fetch", fetchMock);

    const { events, status, connect } = useRunEvents(cursor);
    await connect();

    await vi.waitFor(() => expect(status.value).toBe("waiting"));
    expect(events.value.map((event) => event.sequence)).toEqual([1, 2, 3]);
    expect(events.value[1].eventType).toBe("DECISION_RECORDED");
    expect(sessionStorage.getItem("pulseink.run.7.lastEventId")).toBe("3");
    vi.unstubAllGlobals();
  });

  it("joins multi-line data and de-duplicates/sorts sequences", async () => {
    const cursor = ref({ runId: 7 });
    const multiLineFirst =
      "event: run_state_changed\n" +
      'data: {"eventVersion":"run-event-v1",\n' +
      'data: "runId":7,\n' +
      'data: "sequence":1,\n' +
      'data: "eventType":"RUN_STATE_CHANGED",\n' +
      'data: "payload":{"toState":"RUNNING"},\n' +
      'data: "createdAt":"2026-08-04T12:00:00Z"}\n\n';
    const duplicate = sseLines(7, [
      { sequence: 1, type: "RUN_STATE_CHANGED", payload: { toState: "RUNNING" } }
    ]);
    const outOfOrder = sseLines(7, [
      { sequence: 3, type: "TOOL_CALL_COMPLETED", payload: { qualifiedName: "builtin.search" } },
      { sequence: 2, type: "TOOL_CALL_STARTED", payload: { qualifiedName: "builtin.search" } }
    ]);
    const terminal = sseLines(7, [
      { sequence: 4, type: "RUN_STATE_CHANGED", payload: { toState: "COMPLETED" } }
    ]);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      responseWithBody(chunkedStream(multiLineFirst + duplicate + outOfOrder + terminal, [7, 90]))
    ));

    const { events, status, connect } = useRunEvents(cursor);
    await connect();

    await vi.waitFor(() => expect(status.value).toBe("completed"));
    expect(events.value.map((event) => event.sequence)).toEqual([1, 2, 3, 4]);
    expect(events.value.filter((event) => event.sequence === 1)).toHaveLength(1);
    expect(sessionStorage.getItem("pulseink.run.7.lastEventId")).toBe("4");
    vi.unstubAllGlobals();
  });

  it("keeps only the event sequence in sessionStorage and reads Last-Event-ID", async () => {
    sessionStorage.setItem("pulseink.run.9.lastEventId", "5");
    const cursor = ref({ runId: 9 });
    const fetchMock = vi.fn().mockResolvedValue(responseWithBody(
      chunkedStream(sseLines(9, [
        { sequence: 6, type: "RUN_STATE_CHANGED", payload: { toState: "FAILED" } }
      ]), [200])
    ));
    vi.stubGlobal("fetch", fetchMock);

    const { status, connect } = useRunEvents(cursor);
    await connect();
    await vi.waitFor(() => expect(status.value).toBe("failed"));

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get("Authorization")).toBe("Bearer signed.jwt");
    expect(headers.get("Last-Event-ID")).toBe("5");
    expect(sessionStorage.getItem("pulseink.run.9.lastEventId")).toBe("6");
    for (const key of Object.keys(sessionStorage)) {
      expect(sessionStorage.getItem(key)).not.toContain("signed.jwt");
    }
    vi.unstubAllGlobals();
  });

  it("stops and logs out on 401 without scheduling a reconnect", async () => {
    const cursor = ref({ runId: 7 });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: new Headers(),
      body: null,
      json: async () => ({ code: "UNAUTHENTICATED", message: "authentication is required" })
    } as unknown as Response);
    vi.stubGlobal("fetch", fetchMock);

    const { status, errorMessage, connect } = useRunEvents(cursor);
    await connect();

    expect(status.value).toBe("failed");
    expect(errorMessage.value).toContain("登录");
    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith("/login");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  it("retries with 1s/2s/5s delays and then stops", async () => {
    vi.useFakeTimers();
    const cursor = ref({ runId: 7 });
    const fetchMock = vi.fn().mockRejectedValue(new TypeError("network down"));
    vi.stubGlobal("fetch", fetchMock);

    const { status, errorMessage, connect, stop } = useRunEvents(cursor);
    await connect();
    expect(status.value).toBe("reconnecting");
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(2000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchMock).toHaveBeenCalledTimes(4);

    expect(status.value).toBe("failed");
    expect(errorMessage.value).toContain("重连");
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    stop();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("keeps successful-but-closed SSE reconnects bounded to 1s/2s/5s", async () => {
    vi.useFakeTimers();
    const cursor = ref({ runId: 7 });
    const fetchMock = vi.fn().mockResolvedValue(
      responseWithBody(chunkedStream("", []))
    );
    vi.stubGlobal("fetch", fetchMock);

    const { status, connect, stop } = useRunEvents(cursor);
    await connect();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(2000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(status.value).toBe("failed");

    stop();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("aborts the previous stream when the run switches", async () => {
    const cursor = ref({ runId: 7 });
    const signals: AbortSignal[] = [];
    const fetchMock = vi.fn().mockImplementation(
      (_url: string, init: RequestInit) => {
        signals.push(init.signal!);
        return Promise.resolve(pendingResponse());
      }
    );
    vi.stubGlobal("fetch", fetchMock);

    const { connect, stop } = useRunEvents(cursor);
    connect();
    await nextTick();
    expect(signals[0].aborted).toBe(false);

    cursor.value = { runId: 8 };
    await nextTick();
    await nextTick();

    expect(signals[0].aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1][0])).toContain("/api/runs/8/events");
    stop();
    expect(signals[1].aborted).toBe(true);
    vi.unstubAllGlobals();
  });
});
