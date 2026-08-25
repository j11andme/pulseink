import { ApiError, type ApiErrorBody } from "./http";

export interface ModelChatRequest {
  message: string;
  temperature?: number;
  maxTokens?: number;
}

export type ModelServerEvent =
  | {
      name: "started";
      data: {
        requestId: string;
        provider: string;
        model: string;
      };
    }
  | {
      name: "content_delta";
      data: {
        requestId: string;
        content: string;
      };
    }
  | {
      name: "completed";
      data: {
        requestId: string;
        finishReason: string;
      };
    }
  | {
      name: "error";
      data: {
        requestId: string;
        code: string;
        message: string;
      };
    };

export interface StreamModelChatOptions {
  accessToken: string;
  signal: AbortSignal;
  onEvent: (event: ModelServerEvent) => void;
}

export async function streamModelChat(
  request: ModelChatRequest,
  options: StreamModelChatOptions
): Promise<void> {
  const response = await fetch("/api/model/chat", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${options.accessToken}`,
      Accept: "text/event-stream, application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(request),
    signal: options.signal
  });

  if (!response.ok) {
    const body = await readErrorBody(response);
    throw new ApiError(
      response.status,
      body.code ?? "MODEL_REQUEST_FAILED",
      body.message ?? "模型请求失败，请稍后重试"
    );
  }
  if (!response.body) {
    throw new ApiError(
      502,
      "EMPTY_MODEL_STREAM",
      "模型服务没有返回可读取的数据流"
    );
  }

  await parseEventStream(response.body, options.onEvent);
}

async function parseEventStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: ModelServerEvent) => void
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "";
  let dataLines: string[] = [];

  const dispatch = () => {
    if (!eventName || dataLines.length === 0) {
      eventName = "";
      dataLines = [];
      return;
    }

    const supportedName = toSupportedEventName(eventName);
    if (supportedName) {
      try {
        onEvent({
          name: supportedName,
          data: JSON.parse(dataLines.join("\n"))
        } as ModelServerEvent);
      } catch (error) {
        if (error instanceof ApiError) {
          throw error;
        }
        throw new ApiError(
          502,
          "INVALID_MODEL_STREAM",
          "模型服务返回了无法解析的流式事件"
        );
      }
    }
    eventName = "";
    dataLines = [];
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
    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
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

function toSupportedEventName(
  value: string
): ModelServerEvent["name"] | undefined {
  if (
    value === "started" ||
    value === "content_delta" ||
    value === "completed" ||
    value === "error"
  ) {
    return value;
  }
  return undefined;
}

async function readErrorBody(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return {};
  }
}
