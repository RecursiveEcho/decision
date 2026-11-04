import type { Job } from "./types";
import { clearChatSession } from "./chatStorage";
import { clearMediaSession, purgeExpiredMedia } from "./mediaStorage";
import { OVERVIEW_STORAGE } from "./overviewStorage";
import { KNOWLEDGE_STORAGE } from "./knowledgeStorage";
const SESSION = "decision.session";
let sessionRequests = new AbortController();
export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = "",
  ) {
    super(message);
  }
}
export function token(): string {
  try {
    const s = JSON.parse(sessionStorage.getItem(SESSION) || "null");
    if (s && !(Date.parse(s.expiresAt) > Date.now())) {
      logout();
      return "";
    }
    return s?.accessToken || "";
  } catch {
    return "";
  }
}
export function setSession(s: { accessToken: string; expiresAt: string }) {
  sessionStorage.removeItem(OVERVIEW_STORAGE);
  sessionStorage.removeItem(KNOWLEDGE_STORAGE);
  clearLocalMedia();
  clearChatSession();
  sessionStorage.setItem(
    SESSION,
    JSON.stringify({ ...s, localId: crypto.randomUUID() }),
  );
  void purgeExpiredMedia().catch(() => {});
}
function clearLocalMedia() {
  try {
    const owner = JSON.parse(
      sessionStorage.getItem(SESSION) || "null",
    )?.localId;
    if (owner)
      void clearMediaSession(owner).catch((error: Error) => {
        if (typeof window !== "undefined")
          window.dispatchEvent(
            new CustomEvent("media-cleanup-error", { detail: error.message }),
          );
      });
  } catch {
    /* A malformed session has no usable media owner. */
  }
}
export function sessionExpiry(): number {
  return Date.parse(
    JSON.parse(sessionStorage.getItem(SESSION) || "null")?.expiresAt || "",
  );
}
export function sessionId(): string {
  if (!token()) return "";
  const s = JSON.parse(sessionStorage.getItem(SESSION)!);
  if (!s.localId) {
    s.localId = crypto.randomUUID();
    sessionStorage.setItem(SESSION, JSON.stringify(s));
  }
  return s.localId;
}
export function logout() {
  sessionStorage.removeItem(OVERVIEW_STORAGE);
  sessionStorage.removeItem(KNOWLEDGE_STORAGE);
  sessionRequests.abort();
  clearLocalMedia();
  sessionRequests = new AbortController();
  sessionStorage.removeItem(SESSION);
  clearChatSession();
  window.dispatchEvent(new Event("session-ended"));
}
export const sleep = (ms: number) =>
  new Promise((resolve) => setTimeout(resolve, ms));
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
): Promise<T> {
  const sessionSignal = sessionRequests.signal;
  const checkSession = () => {
    if (sessionSignal.aborted)
      throw new ApiError("已退出登录，停止等待请求。", 499);
  };
  // One key per logical POST, reused across transport retries.
  const idempotencyKey =
    method === "POST" ? key || crypto.randomUUID() : undefined;
  for (let attempt = 0; attempt < 3; attempt++) {
    checkSession();
    let response: Response;
    try {
      response = await fetch(`/api/v1${path}`, {
        method,
        headers: {
          ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
          ...(token() ? { Authorization: `Bearer ${token()}` } : {}),
          ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.any([sessionSignal, AbortSignal.timeout(30000)]),
      });
    } catch {
      checkSession();
      if (attempt < 2) {
        await sleep(500 * 2 ** attempt);
        continue;
      }
      throw new ApiError(
        "连接失败，结果可能尚未返回；请刷新记录确认，避免重复提交。",
      );
    }
    checkSession();
    if (response.status === 401) {
      logout();
      throw new ApiError("访问码无效或登录已过期，请重新登录。", 401);
    }
    if (!response.ok) {
      const data = await response.json().catch(() => null);
      if (
        method === "GET" &&
        [429, 502, 503, 504].includes(response.status) &&
        attempt < 2
      ) {
        await sleep(500 * 2 ** attempt);
        continue;
      }
      throw new ApiError(
        data?.error?.message ||
          (response.status >= 500
            ? `服务暂时不可用（${response.status}），请稍后刷新或确认后端已启动。`
            : `请求未完成（${response.status}）`),
        response.status,
        data?.error?.code || "",
      );
    }
    const result = response.status === 204 ? undefined : await response.json();
    checkSession();
    return result as T;
  }
  throw new ApiError("连接失败");
}
export async function list<T>(path: string): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | null = null;
  const seen = new Set<string>();
  do {
    const page: { items: T[]; nextCursor: string | null } = await request(
      `${path}${path.includes("?") ? "&" : "?"}limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    );
    items.push(...page.items);
    cursor = page.nextCursor;
    if (cursor && seen.has(cursor))
      throw new ApiError("服务返回了重复游标，请刷新重试。");
    if (cursor) seen.add(cursor);
  } while (cursor);
  return items;
}
export async function attachmentBlob(id: string): Promise<Blob> {
  const owner = sessionId(),
    accessToken = token();
  if (!owner) throw new ApiError("请先登录。", 401);
  const response = await fetch(
    `/api/v1/attachments/${encodeURIComponent(id)}`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
      cache: "no-store",
      signal: AbortSignal.any([
        sessionRequests.signal,
        AbortSignal.timeout(30000),
      ]),
    },
  );
  if (owner !== sessionId())
    throw new ApiError("会话已结束，停止读取附件。", 499);
  if (response.status === 401) {
    logout();
    throw new ApiError("登录已过期，请重新登录。", 401);
  }
  if (!response.ok)
    throw new ApiError("附件暂时无法读取，请稍后重试。", response.status);
  const blob = await response.blob();
  if (owner !== sessionId())
    throw new ApiError("会话已结束，停止读取附件。", 499);
  return blob;
}
export async function waitJob(
  job: Job,
  active: () => boolean,
  update: (job: Job) => void,
): Promise<boolean> {
  const deadline = Date.now() + 360000;
  while (active()) {
    update(job);
    if (job.status === "succeeded") return true;
    if (job.status === "failed")
      throw new ApiError(job.error?.message || "处理失败，请查看记录后重试。");
    if (Date.now() > deadline)
      throw new ApiError("处理仍未结束，请稍后刷新；不要重复提交。");
    await sleep(2000);
    if (!active()) return false;
    job = await request<Job>(`/jobs/${encodeURIComponent(job.id)}`);
  }
  return false;
}
