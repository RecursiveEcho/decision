import type { Job, Thinking } from "./types";
import type { LocalFile } from "./mediaStorage";

export const CHAT_STORAGE = "decision.chat-session";
export interface ChatOperation {
  path: string;
  key: string;
  body:
    | { content: string; sourceIds?: string[]; thinking?: Thinking }
    | Record<string, never>;
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
  state: "sending" | "waiting" | "failed" | "stale" | "done";
}
export interface ChatSession {
  owner: string;
  drafts: Record<string, string>;
  pending: PendingChat | null;
  files?: Record<string, LocalFile[]>;
  thinking?: Record<string, Thinking>;
}
export function clearChatSession() {
  sessionStorage.removeItem(CHAT_STORAGE);
}
export function readChatSession(owner: string): ChatSession {
  const empty = { owner, drafts: {}, pending: null };
  try {
    const value = JSON.parse(sessionStorage.getItem(CHAT_STORAGE) || "null");
    if (!owner || !value || value.owner !== owner) {
      clearChatSession();
      return empty;
    }
    const p = value.pending;
    const validText = (v: unknown) =>
      typeof v === "string" && v.length <= 12000;
    const validThinking = (v: unknown) =>
      ["off", "low", "high", "max"].includes(String(v));
    const validFiles = (v: unknown) =>
      Array.isArray(v) &&
      v.length <= 4 &&
      v.every(
        (file) =>
          file &&
          typeof file.id === "string" &&
          typeof file.name === "string" &&
          typeof file.mimeType === "string" &&
          ["image", "document"].includes(file.kind) &&
          Number.isFinite(file.size) &&
          file.size > 0 &&
          file.size <= 8 * 1024 * 1024,
      );
    if (
      !value.drafts ||
      typeof value.drafts !== "object" ||
      !Object.values(value.drafts).every(validText) ||
      (value.files &&
        (typeof value.files !== "object" ||
          !Object.values(value.files).every(validFiles))) ||
      (value.thinking &&
        (typeof value.thinking !== "object" ||
          !Object.values(value.thinking).every(validThinking))) ||
      (p &&
        (!validText(p.content) ||
          (p.files && !validFiles(p.files)) ||
          (p.thinking && !validThinking(p.thinking)) ||
          (p.mediaKey !== undefined && typeof p.mediaKey !== "string") ||
          (p.mediaUnavailable !== undefined &&
            typeof p.mediaUnavailable !== "boolean") ||
          (!p.content.trim() && !p.files?.length) ||
          !["sending", "waiting", "failed", "stale", "done"].includes(
            p.state,
          ) ||
          !(p.problemId === null || typeof p.problemId === "string") ||
          (!p.job && !p.operation && p.state !== "stale") ||
          (p.job &&
            (typeof p.job.id !== "string" ||
              typeof p.job.status !== "string")) ||
          (p.operation &&
            (typeof p.operation.key !== "string" ||
              (p.operation.attachmentSnapshotKey &&
                typeof p.operation.attachmentSnapshotKey !== "string") ||
              !/^\/(conversations|problems\/[\w-]+\/messages|jobs\/[\w-]+\/retry)$/.test(
                p.operation.path,
              ) ||
              !p.operation.body ||
              typeof p.operation.body !== "object"))))
    ) {
      clearChatSession();
      return empty;
    }
    return {
      owner,
      drafts: value.drafts,
      pending: p || null,
      files: value.files || {},
      thinking: value.thinking || {},
    };
  } catch {
    return empty;
  }
}
export function writeChatSession(value: ChatSession) {
  if (!value.owner) return;
  try {
    sessionStorage.setItem(CHAT_STORAGE, JSON.stringify(value));
  } catch {
    throw new Error(
      "浏览器未能暂存这段话，请检查存储权限后再发送。原话仍保留在当前页面。",
    );
  }
}
