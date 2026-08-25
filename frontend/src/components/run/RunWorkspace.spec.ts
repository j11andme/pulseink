import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { nextTick, ref, type Ref } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getRunTrace,
  listRuns,
  type RunEventResponse,
  type RunResponse,
  type RunTraceResponse
} from "../../api/run";
import type { CampaignResponse } from "../../api/campaign";
import type {
  RunEventCursor,
  RunStreamStatus
} from "../../composables/useRunEvents";
import { useAuthStore } from "../../stores/auth";
import RunWorkspace from "./RunWorkspace.vue";

const useRunEventsMock = vi.hoisted(() => vi.fn());
vi.mock("../../composables/useRunEvents", () => ({
  useRunEvents: useRunEventsMock
}));

vi.mock("../../api/run", () => ({
  listRuns: vi.fn(),
  getRunTrace: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() })
}));

const listRunsMock = vi.mocked(listRuns);
const getRunTraceMock = vi.mocked(getRunTrace);

const campaign: CampaignResponse = {
  id: 7,
  name: "PulseInk 秋招发布",
  objective: "objective",
  audience: "audience",
  channels: ["BLOG", "SOCIAL"],
  constraints: [],
  status: "DRAFT",
  createdBy: 1,
  version: 0,
  createdAt: "2026-08-04T12:00:00Z",
  updatedAt: "2026-08-04T12:00:00Z"
};

function runResponse(runId: number, selectedMode: RunResponse["selectedMode"]): RunResponse {
  return {
    runId,
    campaignId: 7,
    requestedPolicy: "ADAPTIVE",
    selectedMode,
    selectorPolicyVersion: "selector-v1",
    reasonCodes: ["DECOMPOSABLE_OR_HIGH_RISK"],
    featureSnapshot: { channelCount: 2 },
    estimatedTokenBudget: 20000,
    state: "WAITING_APPROVAL",
    failureReason: null,
    startedAt: "2026-08-04T12:01:00Z",
    completedAt: null,
    createdAt: "2026-08-04T12:00:00Z",
    updatedAt: "2026-08-04T12:02:00Z"
  };
}

function traceFor(runId: number): RunTraceResponse {
  return {
    run: runResponse(runId, "ORCHESTRATED"),
    lastEventSequence: 5,
    checkpoint: {
      checkpointType: "ARTIFACT",
      schemaVersion: 1,
      lastCompletedRound: 1,
      lastPersistedEventSequence: 5,
      createdAt: "2026-08-04T12:02:00Z",
      budget: { modelCallsUsed: 2, toolCallsUsed: 3, tokensUsed: 4000, reactRoundsUsed: 1 },
      artifacts: [
        {
          artifactId: "run-12-plan-v1",
          taskId: "planner",
          type: "PLAN",
          schemaVersion: "artifact-v1",
          artifactVersion: 1,
          status: "VALID",
          content: { plan: "{\"schemaVersion\":1,\"tasks\":[]}" },
          sourceRefs: [],
          createdAt: "2026-08-04T12:01:00Z"
        },
        {
          artifactId: "run-12-draft-v1",
          taskId: "creator",
          type: "CONTENT_DRAFT",
          schemaVersion: "artifact-v1",
          artifactVersion: 1,
          status: "VALID",
          content: { title: "博客草稿" },
          sourceRefs: ["doc-1"],
          createdAt: "2026-08-04T12:02:00Z"
        }
      ]
    },
    events: [
      {
        sequence: 4,
        eventType: "TOOL_CALL_COMPLETED",
        payload: { qualifiedName: "builtin.knowledge_search", observation: "命中证据" },
        createdAt: "2026-08-04T12:01:30Z"
      } satisfies RunEventResponse
    ]
  };
}

describe("RunWorkspace", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    listRunsMock.mockReset();
    getRunTraceMock.mockReset();
    useRunEventsMock.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  function mountWorkspace(selectedRunId?: number) {
    return mount(RunWorkspace, {
      props: { campaign, selectedRunId: selectedRunId ?? null }
    });
  }

  function installStreamMock(
    statusRef: Ref<RunStreamStatus> = ref("streaming")
  ) {
    const connect = vi.fn();
    const stop = vi.fn();
    const reconnect = vi.fn();
    let cursorRef: Ref<RunEventCursor> | undefined;
    useRunEventsMock.mockImplementation((receivedCursor: Ref<RunEventCursor>) => {
      cursorRef = receivedCursor;
      return {
        events: ref([]),
        status: statusRef,
        errorMessage: ref(""),
        connect,
        stop,
        reconnect
      };
    });
    return {
      connect,
      stop,
      reconnect,
      status: statusRef,
      cursorOf: () => cursorRef?.value as RunEventCursor | undefined
    };
  }

  it("loads the trace snapshot first and then subscribes SSE from lastEventSequence", async () => {
    listRunsMock.mockResolvedValue([runResponse(12, "ORCHESTRATED"), runResponse(11, "REACT")]);
    getRunTraceMock.mockResolvedValue({
      ...traceFor(12),
      run: {
        ...runResponse(12, "ORCHESTRATED"),
        state: "RUNNING"
      }
    });
    const stream = installStreamMock();

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(listRunsMock).toHaveBeenCalledWith("signed.jwt", 7);
    expect(getRunTraceMock).toHaveBeenCalledWith("signed.jwt", 12, expect.any(AbortSignal));
    expect(stream.cursorOf()).toEqual({ runId: 12, lastEventId: "5" });
    expect(stream.connect).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("ORCHESTRATED");
    expect(wrapper.text()).toContain("博客草稿");
    expect(wrapper.text()).toContain("命中证据");
    expect(wrapper.emitted("update:runId")?.[0]).toEqual([12]);
  });

  it("aborts the old trace request and stops the old stream when switching runs", async () => {
    listRunsMock.mockResolvedValue([runResponse(12, "ORCHESTRATED"), runResponse(11, "REACT")]);
    const signals: AbortSignal[] = [];
    let resolveFirst!: (value: RunTraceResponse) => void;
    getRunTraceMock.mockImplementation((_token, runId, signal) => {
      signals.push(signal!);
      if (runId === 12) {
        return new Promise((resolve) => {
          resolveFirst = resolve;
        });
      }
      return Promise.resolve(traceFor(runId));
    });
    const stream = installStreamMock();

    const wrapper = mountWorkspace();
    await flushPromises();
    expect(signals).toHaveLength(1);

    await wrapper.get('[data-testid="run-select-11"]').trigger("click");
    await flushPromises();

    expect(signals[0].aborted).toBe(true);
    expect(stream.stop).toHaveBeenCalled();
    expect(getRunTraceMock).toHaveBeenCalledWith("signed.jwt", 11, signals[1]);
    resolveFirst(traceFor(12));
    await flushPromises();
    expect(wrapper.text()).not.toContain("加载 Trace");
  });

  it("falls back to the latest run for an invalid selected runId and notifies", async () => {
    listRunsMock.mockResolvedValue([runResponse(12, "ORCHESTRATED"), runResponse(11, "REACT")]);
    getRunTraceMock.mockResolvedValue(traceFor(12));
    installStreamMock();

    const wrapper = mountWorkspace(999);
    await flushPromises();

    expect(getRunTraceMock).toHaveBeenCalledWith("signed.jwt", 12, expect.any(AbortSignal));
    expect(wrapper.emitted("update:runId")?.[0]).toEqual([12]);
    expect(wrapper.text()).toContain("未找到指定 Run");
  });

  it("shows an empty state without subscribing when the campaign has no runs", async () => {
    listRunsMock.mockResolvedValue([]);
    const stream = installStreamMock();

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.get('[data-testid="run-workspace-empty"]').text()).toContain("暂无 Run");
    expect(getRunTraceMock).not.toHaveBeenCalled();
    expect(stream.connect).not.toHaveBeenCalled();
    expect(stream.cursorOf()?.runId).toBe(0);
  });

  it("refreshes run history when a newly created selected run is not in the local snapshot", async () => {
    listRunsMock
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([runResponse(13, "ORCHESTRATED")]);
    getRunTraceMock.mockResolvedValue(traceFor(13));
    installStreamMock();

    const wrapper = mountWorkspace();
    await flushPromises();
    expect(wrapper.get('[data-testid="run-workspace-empty"]').text()).toContain("暂无 Run");

    await wrapper.setProps({ selectedRunId: 13 });
    await flushPromises();

    expect(listRunsMock).toHaveBeenCalledTimes(2);
    expect(getRunTraceMock).toHaveBeenCalledWith("signed.jwt", 13, expect.any(AbortSignal));
    expect(wrapper.text()).toContain("ORCHESTRATED");
  });

  it("reloads the durable trace once after the SSE reaches a terminal run state", async () => {
    listRunsMock.mockResolvedValue([runResponse(12, "ORCHESTRATED")]);
    getRunTraceMock.mockResolvedValue(traceFor(12));
    const stream = installStreamMock(ref("streaming"));

    mountWorkspace();
    await flushPromises();
    expect(getRunTraceMock).toHaveBeenCalledTimes(1);

    stream.status.value = "completed";
    await nextTick();
    await flushPromises();

    expect(getRunTraceMock).toHaveBeenCalledTimes(2);
  });

  it("does not reconnect SSE for a durable failed Run and shows its reason", async () => {
    const failedRun: RunResponse = {
      ...runResponse(14, "ORCHESTRATED"),
      state: "FAILED",
      failureReason: "MODEL_FAILURE"
    };
    listRunsMock.mockResolvedValue([failedRun]);
    getRunTraceMock.mockResolvedValue({
      ...traceFor(14),
      run: failedRun,
      lastEventSequence: 18
    });
    const stream = installStreamMock(ref("idle"));

    const wrapper = mountWorkspace(14);
    await flushPromises();

    expect(stream.connect).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("Run 已失败：MODEL_FAILURE");
    expect(wrapper.find('[data-testid="run-stream-reconnect"]').exists()).toBe(false);
  });
});
