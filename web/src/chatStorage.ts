import type { Job } from "./types";

export const CHAT_STORAGE = "decision.chat-session";
export interface ChatOperation {
  path: string;
  key: string;
  body: { content: string; sourceIds?: string[] } | Record<string, never>;
}
export interface PendingChat {
  content: string;
  problemId: string | null;
  operation?: ChatOperation;
  job?: Job;
  state: "sending" | "waiting" | "failed" | "stale" | "done";
}
export interface ChatSession {
  owner: string;
  drafts: Record<string, string>;
  pending: PendingChat | null;
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
    if (
      !value.drafts ||
      typeof value.drafts !== "object" ||
      !Object.values(value.drafts).every(validText) ||
      (p &&
        (!validText(p.content) ||
          !p.content.trim() ||
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
              !/^\/(conversations|problems\/[\w-]+\/messages|jobs\/[\w-]+\/retry)$/.test(
                p.operation.path,
              ) ||
              !p.operation.body ||
              typeof p.operation.body !== "object"))))
    ) {
      clearChatSession();
      return empty;
    }
    return { owner, drafts: value.drafts, pending: p || null };
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
