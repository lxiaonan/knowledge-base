import type { Message, Source } from "./types";

let csrfToken = "";
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function csrf() {
  const response = await fetch("/api/auth/csrf", {
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error("无法连接服务，请稍后重试");
  csrfToken = (await response.json()).token;
}
async function ensure(response: Response) {
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    if (response.status === 401)
      window.dispatchEvent(new Event("session-expired"));
    throw new ApiError(
      error.message || "操作失败，请稍后重试",
      response.status,
    );
  }
  return response;
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (method !== "GET" && !csrfToken) await csrf();
  const multipart = body instanceof FormData;
  const headers: Record<string, string> = { "X-CSRF-TOKEN": csrfToken };
  if (body && !multipart) headers["Content-Type"] = "application/json";
  const response = await ensure(
    await fetch(`/api${path}`, {
      method,
      headers,
      credentials: "same-origin",
      body: body ? (multipart ? body : JSON.stringify(body)) : undefined,
    }),
  );
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
export async function streamChat(
  id: string,
  question: string,
  signal: AbortSignal,
  handlers: {
    sources: (sources: Source[]) => void;
    token: (text: string) => void;
    done: (message: Message) => void;
    context?: (context: NonNullable<Message["context"]>) => void;
    status?: (message: string) => void;
  },
  useContext = false,
) {
  if (!csrfToken) await csrf();
  const response = await ensure(
    await fetch(`/api/conversations/${id}/chat`, {
      method: "POST",
      credentials: "same-origin",
      signal,
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": csrfToken,
      },
      body: JSON.stringify({ question, useContext }),
    }),
  );
  if (!response.body) throw new Error("浏览器不支持流式响应");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "",
    complete = false;
  function dispatch(frame: string) {
    const lines = frame.split("\n");
    const event = lines
      .find((line) => line.startsWith("event:"))
      ?.slice(6)
      .trim();
    const data = lines
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trimStart())
      .join("\n");
    if (!data) return;
    const value = JSON.parse(data);
    if (event === "context") handlers.context?.(value);
    else if (event === "status") handlers.status?.(value.message);
    else if (event === "sources") handlers.sources(value);
    else if (event === "token") handlers.token(value.text);
    else if (event === "done") {
      handlers.done(value);
      complete = true;
    } else if (event === "error") throw new Error(value.message);
  }
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += done
        ? decoder.decode()
        : decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n");
      let boundary: number;
      while ((boundary = buffer.indexOf("\n\n")) >= 0) {
        dispatch(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
      }
      if (done) break;
    }
    if (buffer.trim()) dispatch(buffer);
    if (!complete) throw new Error("连接中断，回答尚未完成，请重新提问");
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
