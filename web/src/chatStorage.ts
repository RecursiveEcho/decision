import type { Job, Thinking } from "./types";
import type { LocalFile } from "./mediaStorage";
export const CHAT_STORAGE = "decision.chat-session";
export interface ChatOperation {
  path: string;
  key: string;
  body: {
    content?: string;
    sourceIds?: string[];
    thinking?: Thinking;
    provider?: string;
    model?: string;
  };
  attachmentSnapshotKey?: string;
}
export interface PendingChat {
  content: string;
  files?: LocalFile[];
  thinking?: Thinking;
  mediaKey?: string;
  mediaUnavailable?: boolean;
  problemId: string | null;
  operation?: ChatOperation;
  job?: Job;
  state:
    | "sending"
    | "waiting"
    | "failed"
    | "stale"
    | "done"
    | "cancelled"
    | "queued";
  cursor?: number;
  error?: string;
  stageLabel?: string;
}
export interface ChatSession {
  owner: string;
  unread?: Record<string, boolean>;
  drafts: Record<string, string>;
  pending: PendingChat | null;
  pendings?: Record<string, PendingChat>;
  queues?: Record<string, PendingChat[]>;
  files?: Record<string, LocalFile[]>;
  thinking?: Record<string, Thinking>;
  models?: Record<string, { provider?: string; model?: string }>;
}
function storage(): Storage {
  return typeof localStorage === "undefined" ? sessionStorage : localStorage;
}
const key = (owner: string) => `${CHAT_STORAGE}:${encodeURIComponent(owner)}`;
export function clearChatSession(owner?: string) {
  if (owner) storage().removeItem(key(owner));
  sessionStorage.removeItem(CHAT_STORAGE);
}
function validPending(p: any): boolean {
  return (
    !!p &&
    typeof p.content === "string" &&
    p.content.length <= 12000 &&
    (p.problemId === null || typeof p.problemId === "string") &&
    [
      "sending",
      "waiting",
      "failed",
      "stale",
      "done",
      "cancelled",
      "queued",
    ].includes(p.state) &&
    (!p.operation ||
      (typeof p.operation.key === "string" &&
        /^\/(conversations|problems\/[\w-]+\/messages|jobs\/[\w-]+\/retry)$/.test(
          p.operation.path,
        ) &&
        typeof p.operation.body === "object")) &&
    (!p.job ||
      (typeof p.job.id === "string" && typeof p.job.status === "string")) &&
    (!p.files || validFiles(p.files))
  );
}
function validFiles(files: any): boolean {
  return (
    Array.isArray(files) &&
    files.length <= 4 &&
    files.every(
      (f) =>
        f &&
        typeof f.id === "string" &&
        typeof f.name === "string" &&
        typeof f.mimeType === "string" &&
        ["image", "document"].includes(f.kind) &&
        f.size > 0 &&
        f.size <= 8 * 1024 * 1024,
    ) &&
    files.reduce((n, f) => n + f.size, 0) <= 16 * 1024 * 1024
  );
}
export function readChatSession(owner: string): ChatSession {
  const empty = { owner, drafts: {}, pending: null };
  try {
    const value = JSON.parse(
      storage().getItem(key(owner)) ||
        sessionStorage.getItem(CHAT_STORAGE) ||
        "null",
    );
    if (!owner || !value || value.owner !== owner) return empty;
    if (
      !value.drafts ||
      typeof value.drafts !== "object" ||
      !Object.values(value.drafts).every(
        (v) => typeof v === "string" && v.length <= 12000,
      ) ||
      (value.pending && !validPending(value.pending)) ||
      (value.pendings && !Object.values(value.pendings).every(validPending)) ||
      (value.queues &&
        !Object.values(value.queues).every(
          (q) => Array.isArray(q) && q.length <= 20 && q.every(validPending),
        )) ||
      (value.files && !Object.values(value.files).every(validFiles)) ||
      (value.thinking &&
        !Object.values(value.thinking).every((v) =>
          ["off", "low", "high", "max"].includes(String(v)),
        ))
    )
      return empty;
    return { ...empty, ...value, owner };
  } catch {
    return empty;
  }
}
export function writeChatSession(value: ChatSession) {
  if (!value.owner) return;
  try {
    storage().setItem(key(value.owner), JSON.stringify(value));
    // Old session-only clients can recover a write whose HTTP result is unknown.
    sessionStorage.setItem(CHAT_STORAGE, JSON.stringify(value));
  } catch {
    throw new Error("未能保存本机草稿，请检查存储空间。原话仍保留在当前页面。");
  }
}
