import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../stores/auth";
import { useModelStream } from "./useModelStream";

const push = vi.fn();

vi.mock("vue-router", () => ({
  useRouter: () => ({ push })
}));

describe("useModelStream", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    useAuthStore().acceptSession({
      accessToken: "signed.jwt",
      expiresIn: 1800,
      user: { id: 1, username: "demo", role: "EDITOR" }
    });
  });

  it("parses fragmented SSE chunks and grows the visible content", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({
      start(streamController) {
        controller = streamController;
      }
    });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" }
      })
    );
    const stream = useModelStream();

    const completed = stream.start("介绍 PulseInk", 0.3, 512);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/model/chat",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          Authorization: "Bearer signed.jwt",
          Accept: "text/event-stream, application/json"
        }),
        body: JSON.stringify({
          message: "介绍 PulseInk",
          temperature: 0.3,
          maxTokens: 512
        })
      })
    );

    enqueue(
      controller,
      'event:started\ndata:{"requestId":"r1","provider":"fake","model":"pulseink-fake"}\n\n' +
        'event:content_delta\ndata:{"requestId":"r1","content":"Pulse"}\n\n'
    );
    await vi.waitFor(() => expect(stream.content.value).toBe("Pulse"));
    expect(stream.status.value).toBe("streaming");

    enqueue(
      controller,
      'event:content_delta\ndata:{"requestId":"r1","content":"In'
    );
    enqueue(
      controller,
      'k"}\n\nevent:completed\ndata:{"requestId":"r1","finishReason":"STOP"}\n\n'
    );
    controller.close();
    await completed;

    expect(stream.content.value).toBe("PulseInk");
    expect(stream.status.value).toBe("completed");
    expect(stream.requestId.value).toBe("r1");
    expect(stream.provider.value).toBe("fake");
  });

  it("aborts an active request without reporting a provider failure", async () => {
    let requestSignal!: AbortSignal;
    vi.spyOn(globalThis, "fetch").mockImplementation((_input, init) => {
      requestSignal = init?.signal as AbortSignal;
      return new Promise<Response>((_resolve, reject) => {
        requestSignal.addEventListener("abort", () => {
          reject(new DOMException("aborted", "AbortError"));
        });
      });
    });
    const stream = useModelStream();

    const pending = stream.start("stop this request");
    await vi.waitFor(() => expect(requestSignal).toBeDefined());
    stream.stop();
    await pending;

    expect(requestSignal.aborted).toBe(true);
    expect(stream.status.value).toBe("idle");
    expect(stream.errorMessage.value).toBe("");
  });

  it("clears the in-memory session and returns to login on 401", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "UNAUTHORIZED",
          message: "token expired"
        }),
        {
          status: 401,
          headers: { "Content-Type": "application/json" }
        }
      )
    );
    const auth = useAuthStore();
    const stream = useModelStream();

    await stream.start("hello");

    expect(auth.isAuthenticated).toBe(false);
    expect(push).toHaveBeenCalledWith("/login");
    expect(stream.status.value).toBe("failed");
    expect(stream.errorMessage.value).toContain("登录");
  });
});

function enqueue(
  controller: ReadableStreamDefaultController<Uint8Array>,
  value: string
) {
  controller.enqueue(new TextEncoder().encode(value));
}
