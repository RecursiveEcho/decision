import { ref } from "vue";
import { ApiError, request, sessionId, waitJob } from "./api";
import {
  clearChatSession,
  readChatSession,
  writeChatSession,
  type PendingChat,
} from "./chatStorage";
import type { Job, Problem } from "./types";

export function useChatFlow(callbacks: {
  accepted: (id: string, problem?: Problem) => void;
  refresh: () => Promise<void>;
}) {
  const pending = ref<PendingChat | null>(null);
  const drafts = ref<Record<string, string>>({});
  let owner = "",
    disposed = false;
  const active = (p: PendingChat) =>
    !disposed && !!owner && owner === sessionId() && pending.value === p;
  function save() {
    if (!owner || owner !== sessionId())
      throw new ApiError("已退出登录，停止等待请求。", 499);
    writeChatSession({ owner, drafts: drafts.value, pending: pending.value });
  }
  function restore() {
    owner = sessionId();
    const stored = readChatSession(owner);
    drafts.value = stored.drafts;
    pending.value = stored.pending;
  }
  function setDraft(target: string, value: string) {
    drafts.value[target] = value;
    save();
  }
  function clear() {
    owner = "";
    pending.value = null;
    drafts.value = {};
    clearChatSession();
  }
  async function finish(p: PendingChat) {
    if (!active(p)) return;
    p.state = "done";
    save();
    await callbacks.refresh();
    if (active(p)) {
      pending.value = null;
      save();
    }
  }
  async function observe(p: PendingChat, reload = false) {
    if (!p.job || !active(p)) return;
    if (reload) {
      const j = await request<Job>(`/jobs/${p.job.id}`);
      if (!active(p)) return;
      p.job = j;
    }
    try {
      const completed = await waitJob(
        p.job,
        () => active(p),
        (j) => {
          p.job = j;
          p.state =
            j.status === "failed"
              ? "failed"
              : j.status === "succeeded"
                ? "done"
                : "waiting";
          save();
        },
      );
      if (completed && active(p)) await finish(p);
    } catch (e) {
      if (active(p)) await callbacks.refresh().catch(() => {});
      throw e;
    }
  }
  async function perform(p: PendingChat) {
    const op = p.operation;
    if (!op || !active(p)) return;
    // Save both initial and retry operations before any write, including their immutable payload/key.
    save();
    try {
      const result = await request<Job | { problem: Problem; job: Job }>(
        op.path,
        "POST",
        op.body,
        op.key,
      );
      if (!active(p)) return;
      if ("problem" in result) {
        p.problemId = result.problem.id;
        p.job = result.job;
        callbacks.accepted(result.problem.id, result.problem);
      } else {
        p.job = result;
        callbacks.accepted(p.problemId!);
      }
      p.operation = undefined;
      p.state = "waiting";
      save();
      // The server has the original sentence now. Loading it is read-only and may be retried.
      await callbacks.refresh().catch(() => {});
      await observe(p);
    } catch (e) {
      if (!active(p)) return;
      if (e instanceof ApiError && e.code === "JOB_NOT_RETRYABLE" && p.job) {
        p.operation = undefined;
        save();
        const current = await request<Job>(`/jobs/${p.job.id}`);
        if (!active(p)) return;
        p.job = current;
        if (current.status !== "failed") return observe(p);
        p.state = "stale";
        save();
      } else if (
        e instanceof ApiError &&
        [400, 404, 409, 413].includes(e.status)
      ) {
        p.operation = undefined;
        p.state = "stale";
        save();
      }
      throw e;
    }
  }
  async function send(targetId: string | null, sourceIds: string[] = []) {
    if (pending.value)
      throw new Error("上一段话还在处理中，请先恢复或重试回答。");
    const target = targetId || "new";
    const content = drafts.value[target] || "";
    if (!content.trim() || content.length > 12000)
      throw new Error("请写一句想聊的话，最多12000字。");
    pending.value = {
      content,
      problemId: targetId,
      state: "sending",
      operation: {
        path: targetId ? `/problems/${targetId}/messages` : "/conversations",
        key: crypto.randomUUID(),
        body: targetId ? { content, sourceIds } : { content },
      },
    };
    save();
    drafts.value[target] = "";
    save();
    await perform(pending.value);
  }
  async function resume(retryFailed = false) {
    const p = pending.value;
    if (!p || !active(p)) return;
    if (p.state === "stale")
      throw new Error("这段话的依据已发生变化，请调整原话后再发送。");
    if (p.operation) return perform(p);
    if (p.job?.status === "failed" && retryFailed) {
      p.operation = {
        path: `/jobs/${p.job.id}/retry`,
        key: crypto.randomUUID(),
        body: {},
      };
      save();
      return perform(p);
    }
    return observe(p, true);
  }
  function editOriginal() {
    const p = pending.value;
    if (!p || p.state !== "stale") return;
    drafts.value[p.problemId || "new"] = p.content;
    pending.value = null;
    save();
  }
  function leaveFinished() {
    const p = pending.value;
    if (
      !p ||
      p.operation ||
      !p.job ||
      !["failed", "succeeded"].includes(p.job.status)
    )
      throw new Error("这段话还未确认处理结果，请先恢复发送。");
    // This only releases local waiting state. The original sentence remains in server history.
    pending.value = null;
    save();
  }
  return {
    pending,
    drafts,
    restore,
    setDraft,
    clear,
    send,
    resume,
    editOriginal,
    leaveFinished,
    dispose: () => {
      disposed = true;
    },
  };
}
