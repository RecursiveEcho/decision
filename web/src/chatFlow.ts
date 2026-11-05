import { ref } from "vue";
import { ApiError, request, sessionId, sessionExpiry, waitJob } from "./api";
import {
  clearChatSession,
  readChatSession,
  writeChatSession,
  type PendingChat,
} from "./chatStorage";
import type { Job, Problem, Thinking } from "./types";
import {
  getMedia,
  putMedia,
  deleteMedia,
  MissingMediaError,
  localMetadata,
  type LocalFile,
  type StoredFile,
} from "./mediaStorage";
import { checkFileLimits, prepareFile, toDataUrl } from "./attachments";

export function useChatFlow(callbacks: {
  accepted: (id: string, problem?: Problem) => void;
  refresh: () => Promise<void>;
}) {
  const pending = ref<PendingChat | null>(null);
  const drafts = ref<Record<string, string>>({});
  const files = ref<Record<string, LocalFile[]>>({});
  const thinking = ref<Record<string, Thinking>>({});
  const preparing = ref(false);
  let stagingSend = false;
  let owner = "",
    disposed = false;
  const active = (p: PendingChat) =>
    !disposed && !!owner && owner === sessionId() && pending.value === p;
  function save() {
    if (!owner || owner !== sessionId())
      throw new ApiError("已退出登录，停止等待请求。", 499);
    writeChatSession({
      owner,
      drafts: drafts.value,
      pending: pending.value,
      files: files.value,
      thinking: thinking.value,
    });
  }
  function restore() {
    owner = sessionId();
    const stored = readChatSession(owner);
    drafts.value = stored.drafts;
    pending.value = stored.pending;
    files.value = stored.files || {};
    thinking.value = stored.thinking || {};
  }
  function validOwner(value: string) {
    return !disposed && !!value && owner === value && sessionId() === value;
  }
  function release(ids: string[]) {
    const capturedOwner = owner;
    const referenced = new Set(
      Object.values(files.value)
        .flat()
        .map((file) => file.id),
    );
    for (const file of pending.value?.files || []) referenced.add(file.id);
    if (pending.value?.mediaKey) referenced.add(pending.value.mediaKey);
    void deleteMedia(
      capturedOwner,
      ids.filter((id) => !referenced.has(id)),
      () => validOwner(capturedOwner),
    ).catch(() => {
      if (validOwner(capturedOwner))
        window.dispatchEvent(
          new CustomEvent("media-cleanup-error", {
            detail: "部分本机附件缓存未能清理，退出时将再次清理。",
          }),
        );
    });
  }
  const mediaIds = (p: PendingChat) => [
    ...(p.files || []).map((file) => file.id),
    ...(p.mediaKey ? [p.mediaKey] : []),
  ];
  async function addFiles(target: string, incoming: File[]) {
    if (preparing.value || pending.value || stagingSend)
      throw new Error("附件正在处理，请稍等。");
    const currentOwner = owner;
    const current = files.value[target] || [];
    checkFileLimits([...current, ...incoming]);
    preparing.value = true;
    try {
      const prepared: StoredFile[] = [];
      for (const file of incoming) {
        prepared.push(await prepareFile(file));
        if (!validOwner(currentOwner)) throw new ApiError("会话已结束。", 499);
      }
      checkFileLimits([...current, ...prepared]);
      for (const file of prepared)
        await putMedia(currentOwner, file.id, [file], sessionExpiry(), () =>
          validOwner(currentOwner),
        );
      if (!validOwner(currentOwner)) throw new ApiError("会话已结束。", 499);
      files.value[target] = [...current, ...prepared.map(localMetadata)];
      save();
    } finally {
      preparing.value = false;
    }
  }
  function removeFile(target: string, id: string) {
    if (pending.value || preparing.value || stagingSend) return;
    files.value[target] = (files.value[target] || []).filter(
      (file) => file.id !== id,
    );
    save();
    release([id]);
  }
  function setThinking(target: string, value: Thinking) {
    if (pending.value || stagingSend) return;
    thinking.value[target] = value;
    save();
  }
  function setDraft(target: string, value: string) {
    drafts.value[target] = value;
    save();
  }
  function discardDraft(target: string) {
    const ids = (files.value[target] || []).map((file) => file.id);
    delete drafts.value[target];
    delete files.value[target];
    delete thinking.value[target];
    save();
    if (ids.length) release(ids);
  }
  function discardMissingConversation(id: string) {
    const operation = pending.value;
    if (operation?.problemId !== id) return;
    pending.value = null;
    save();
    release(mediaIds(operation));
  }
  function clear() {
    owner = "";
    pending.value = null;
    drafts.value = {};
    files.value = {};
    thinking.value = {};
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
      release(mediaIds(p));
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
      let body: unknown = op.body;
      if (op.attachmentSnapshotKey) {
        const capturedOwner = owner;
        const snapshot = await getMedia(
          capturedOwner,
          op.attachmentSnapshotKey,
          () => active(p),
        );
        const attachments = await Promise.all(
          snapshot.map(async (file) => ({
            name: file.name,
            dataUrl: await toDataUrl(file.blob),
          })),
        );
        if (!active(p)) return;
        body = { ...op.body, attachments };
      }
      const result = await request<Job | { problem: Problem; job: Job }>(
        op.path,
        "POST",
        body,
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
      if (p.problemId) thinking.value[p.problemId] = p.thinking || "off";
      p.operation = undefined;
      p.state = "waiting";
      save();
      // The server has the original sentence now. Loading it is read-only and may be retried.
      await callbacks.refresh().catch(() => {});
      await observe(p);
    } catch (e) {
      if (!active(p)) return;
      if (e instanceof MissingMediaError) {
        p.mediaUnavailable = true;
        save();
      } else if (
        e instanceof ApiError &&
        e.code === "JOB_NOT_RETRYABLE" &&
        p.job
      ) {
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
    if (pending.value || stagingSend)
      throw new Error("上一段话还在处理中，请先恢复或重试回答。");
    const target = targetId || "new";
    const content = drafts.value[target] || "";
    const selectedFiles = [...(files.value[target] || [])];
    const selectedThinking = thinking.value[target] || "off";
    if ((!content.trim() && !selectedFiles.length) || content.length > 12000)
      throw new Error("请写一句想聊的话，最多12000字。");
    if (preparing.value) throw new Error("附件正在处理，请稍等。");
    const key = crypto.randomUUID();
    if (selectedFiles.length) {
      stagingSend = true;
      preparing.value = true;
      const capturedOwner = owner;
      try {
        const originals = (
          await Promise.all(
            selectedFiles.map((file) =>
              getMedia(capturedOwner, file.id, () => validOwner(capturedOwner)),
            ),
          )
        ).flat();
        checkFileLimits(originals);
        await putMedia(capturedOwner, key, originals, sessionExpiry(), () =>
          validOwner(capturedOwner),
        );
        if (!validOwner(capturedOwner)) throw new ApiError("会话已结束。", 499);
      } finally {
        stagingSend = false;
        preparing.value = false;
      }
    }
    pending.value = {
      content,
      files: selectedFiles,
      thinking: selectedThinking,
      ...(selectedFiles.length ? { mediaKey: key } : {}),
      problemId: targetId,
      state: "sending",
      operation: {
        path: targetId ? `/problems/${targetId}/messages` : "/conversations",
        key,
        ...(selectedFiles.length ? { attachmentSnapshotKey: key } : {}),
        body: targetId
          ? { content, sourceIds: [...sourceIds], thinking: selectedThinking }
          : { content, thinking: selectedThinking },
      },
    };
    save();
    drafts.value[target] = "";
    files.value[target] = [];
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
    files.value[p.problemId || "new"] = p.files || [];
    thinking.value[p.problemId || "new"] = p.thinking || "off";
    pending.value = null;
    save();
    if (p.mediaKey) release([p.mediaKey]);
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
    release(mediaIds(p));
  }
  function leaveMissingMedia() {
    const p = pending.value;
    if (!p?.mediaUnavailable) return;
    // Explicitly dismiss waiting only; never restore as a new write or change its idempotency key.
    pending.value = null;
    save();
    release(mediaIds(p));
  }
  return {
    pending,
    drafts,
    files,
    thinking,
    preparing,
    addFiles,
    removeFile,
    setThinking,
    restore,
    setDraft,
    discardDraft,
    discardMissingConversation,
    clear,
    send,
    resume,
    editOriginal,
    leaveFinished,
    leaveMissingMedia,
    dispose: () => {
      disposed = true;
    },
  };
}
