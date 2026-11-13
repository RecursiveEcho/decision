import { computed, ref } from "vue";
import { ApiError, accountId, request, sessionId, sleep } from "./api";
import {
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
  accepted: (id: string, problem?: Problem, sourceTarget?: string) => void;
  refresh: (id?: string) => Promise<void>;
}) {
  const target = ref("new");
  const pendings = ref<Record<string, PendingChat>>({});
  const queues = ref<Record<string, PendingChat[]>>({});
  const pausedQueues = ref<Record<string, boolean>>({});
  const drafts = ref<Record<string, string>>({});
  const files = ref<Record<string, LocalFile[]>>({});
  const thinking = ref<Record<string, Thinking>>({});
  const models = ref<Record<string, { provider?: string; model?: string }>>({});
  const preparing = ref(false);
  const unread = ref<Record<string, boolean>>({});
  const pending = computed({
    get: () => pendings.value[target.value] || null,
    set: (value: PendingChat | null) => {
      if (value) pendings.value[target.value] = value;
      else delete pendings.value[target.value];
    },
  });
  let owner = "",
    epoch = "",
    disposed = false;
  const observers = new Set<string>();
  const sends = new Set<string>();
  const draftRevisions = new Map<string, number>();
  const fileOperations = new Set<{ target: string }>();
  const validOwner = () =>
    !disposed && !!owner && owner === accountId() && epoch === sessionId();
  const active = (p: PendingChat) =>
    validOwner() && Object.values(pendings.value).includes(p);
  const mediaIds = (p: PendingChat) => [
    ...(p.files || []).map((f) => f.id),
    ...(p.mediaKey ? [p.mediaKey] : []),
  ];
  function save() {
    if (!validOwner()) throw new ApiError("已退出登录，停止等待请求。", 499);
    writeChatSession({
      owner,
      unread: unread.value,
      drafts: drafts.value,
      pending: pending.value,
      pendings: pendings.value,
      queues: queues.value,
      files: files.value,
      thinking: thinking.value,
      models: models.value,
    });
  }
  function setTarget(id: string) {
    target.value = id || "new";
    if (unread.value[target.value]) {
      delete unread.value[target.value];
      if (validOwner()) save();
    }
  }
  function restore() {
    owner = accountId();
    epoch = sessionId();
    disposed = false;
    const value = readChatSession(owner);
    unread.value = value.unread || {};
    drafts.value = value.drafts;
    files.value = value.files || {};
    thinking.value = value.thinking || {};
    models.value = value.models || {};
    pendings.value =
      value.pendings ||
      (value.pending
        ? { [value.pending.problemId || "new"]: value.pending }
        : {});
    queues.value = value.queues || {};
    pausedQueues.value = Object.fromEntries(
      Object.keys(queues.value).map((id) => [id, true]),
    );
    if (value.pending && target.value === "new")
      target.value = value.pending.problemId || "new";
  }
  function release(ids: string[]) {
    const captured = owner,
      capturedEpoch = epoch;
    const referenced = new Set([
      ...Object.values(files.value)
        .flat()
        .map((f) => f.id),
      ...Object.values(pendings.value).flatMap(mediaIds),
      ...Object.values(queues.value).flat().flatMap(mediaIds),
    ]);
    void deleteMedia(
      captured,
      ids.filter((id) => !referenced.has(id)),
      () => validOwner() && epoch === capturedEpoch,
    ).catch(() => {});
  }
  async function addFiles(id: string, incoming: File[]) {
    if (preparing.value) throw new Error("附件正在处理，请稍等。");
    const capturedEpoch = epoch,
      current = files.value[id] || [];
    checkFileLimits([...current, ...incoming]);
    preparing.value = true;
    const operation = { target: id };
    fileOperations.add(operation);
    try {
      const prepared: StoredFile[] = [];
      for (const file of incoming) {
        prepared.push(await prepareFile(file));
        if (!validOwner() || epoch !== capturedEpoch)
          throw new ApiError("会话已结束。", 499);
      }
      checkFileLimits([...(files.value[operation.target] || []), ...prepared]);
      for (const file of prepared)
        await putMedia(
          owner,
          file.id,
          [file],
          Date.now() + 30 * 86400000,
          () => validOwner() && epoch === capturedEpoch,
        );
      if (!validOwner() || epoch !== capturedEpoch) return;
      files.value[operation.target] = [
        ...(files.value[operation.target] || []),
        ...prepared.map(localMetadata),
      ];
      save();
    } finally {
      fileOperations.delete(operation);
      if (epoch === capturedEpoch) preparing.value = false;
    }
  }
  function removeFile(id: string, fileId: string) {
    if (preparing.value) return;
    files.value[id] = (files.value[id] || []).filter((f) => f.id !== fileId);
    save();
    release([fileId]);
  }
  function setThinking(id: string, value: Thinking) {
    thinking.value[id] = value;
    save();
  }
  function setModel(id: string, value: { provider?: string; model?: string }) {
    models.value[id] = value;
    save();
  }
  function setDraft(id: string, value: string) {
    draftRevisions.set(id, (draftRevisions.get(id) || 0) + 1);
    drafts.value[id] = value;
    save();
  }
  function discardDraft(id: string) {
    draftRevisions.set(id, (draftRevisions.get(id) || 0) + 1);
    const ids = (files.value[id] || []).map((f) => f.id);
    delete drafts.value[id];
    delete files.value[id];
    delete thinking.value[id];
    save();
    release(ids);
  }
  function discardMissingConversation(id: string) {
    const p = pendings.value[id];
    delete pendings.value[id];
    const queued = queues.value[id] || [];
    delete queues.value[id];
    save();
    if (p) release(mediaIds(p));
    release(queued.flatMap(mediaIds));
  }
  function clear() {
    owner = "";
    epoch = "";
    preparing.value = false;
    pausedQueues.value = {};
    target.value = "new";
    draftRevisions.clear();
    fileOperations.clear();
    pendings.value = {};
    queues.value = {};
    drafts.value = {};
    files.value = {};
    thinking.value = {};
    models.value = {};
    unread.value = {};
    observers.clear();
    sends.clear();
  }
  async function finish(p: PendingChat) {
    if (!active(p)) return;
    p.state = "done";
    save();
    await callbacks.refresh(p.problemId || undefined);
    if (active(p)) {
      const id = p.problemId || "new";
      delete pendings.value[id];
      if (target.value !== id) unread.value[id] = true;
      save();
      release(mediaIds(p));
      void drain(id).catch(() => {});
    }
  }
  async function observe(p: PendingChat, reload = false) {
    if (!p.job || !active(p)) return;
    const jobId = p.job.id,
      observation = epoch + ":" + p.job.id;
    if (observers.has(observation)) return;
    observers.add(observation);
    try {
      if (reload) {
        const fresh = await request<Job>(`/jobs/${jobId}`);
        if (!active(p)) return;
        if (!["succeeded", "failed", "cancelled"].includes(p.job.status))
          p.job = fresh;
      }
      while (active(p) && p.job) {
        if (p.job.status === "succeeded") {
          await finish(p);
          return;
        }
        if (p.job.status === "failed") {
          p.state = "failed";
          p.error = p.job.error?.message || "回答未完成";
          pausedQueues.value[p.problemId || "new"] = true;
          save();
          throw new Error(p.error);
        }
        if (p.job.status === "cancelled") {
          p.state = "cancelled";
          pausedQueues.value[p.problemId || "new"] = true;
          save();
          return;
        }
        const response = await request<{
          items: { id: number; type: string; data: { label?: string } }[];
          nextCursor: number;
          job: Job;
        }>(`/jobs/${jobId}/events?after=${p.cursor || 0}`);
        if (!active(p)) return;
        if (["succeeded", "failed", "cancelled"].includes(p.job.status))
          continue;
        const previousCursor = p.cursor || 0;
        if (response.nextCursor < previousCursor) {
          await sleep(400);
          continue;
        }
        p.job = response.job;
        p.cursor = response.nextCursor;
        p.error = undefined;
        for (const event of response.items)
          if (
            event.id > previousCursor &&
            event.type === "stage" &&
            event.data.label
          )
            p.stageLabel = event.data.label;
        // The server snapshot is authoritative. Never append deltas to the same snapshot.
        p.state = "waiting";
        save();
        if (["queued", "running"].includes(p.job.status)) await sleep(400);
      }
    } catch (e) {
      if (active(p)) {
        p.error = e instanceof Error ? e.message : "连接中断，可继续获取回答";
        save();
        await callbacks.refresh(p.problemId || undefined).catch(() => {});
      }
      throw e;
    } finally {
      observers.delete(observation);
    }
  }
  async function perform(p: PendingChat) {
    const op = p.operation;
    if (!op || !active(p)) return;
    save();
    try {
      let body: unknown = op.body;
      if (op.attachmentSnapshotKey) {
        const snapshot = await getMedia(owner, op.attachmentSnapshotKey, () =>
          active(p),
        );
        const attachments = await Promise.all(
          snapshot.map(async (f) => ({
            name: f.name,
            dataUrl: await toDataUrl(f.blob),
          })),
        );
        if (!active(p)) return;
        body = { ...op.body, attachments };
      }
      const sourceTarget = p.problemId || "new";
      const result = await request<Job | { problem: Problem; job: Job }>(
        op.path,
        "POST",
        body,
        op.key,
      );
      if (!active(p)) return;
      if ("problem" in result) {
        delete pendings.value[sourceTarget];
        p.problemId = result.problem.id;
        p.job = result.job;
        pendings.value[p.problemId] = p;
        if (sourceTarget === "new") {
          for (const operation of fileOperations)
            if (operation.target === sourceTarget)
              operation.target = p.problemId;
          drafts.value[p.problemId] = drafts.value.new || "";
          files.value[p.problemId] = files.value.new || [];
          thinking.value[p.problemId] =
            thinking.value.new || p.thinking || "off";
          models.value[p.problemId] = models.value.new || {
            provider: op.body.provider,
            model: op.body.model,
          };
          delete drafts.value.new;
          delete files.value.new;
        }
        if (target.value === sourceTarget) target.value = p.problemId;
        callbacks.accepted(p.problemId, result.problem, sourceTarget);
      } else {
        p.job = result;
        callbacks.accepted(p.problemId!, undefined, sourceTarget);
      }
      p.operation = undefined;
      p.state = "waiting";
      p.error = undefined;
      save();
      await callbacks.refresh(p.problemId || undefined).catch(() => {});
      await observe(p);
    } catch (e) {
      if (!active(p)) return;
      p.error = e instanceof Error ? e.message : "发送未完成";
      if (e instanceof MissingMediaError) p.mediaUnavailable = true;
      else if (
        e instanceof ApiError &&
        [400, 404, 409, 413].includes(e.status)
      ) {
        p.operation = undefined;
        p.state = "stale";
      }
      save();
      throw e;
    }
  }
  async function snapshot(
    targetId: string | null,
    sourceIds: string[],
  ): Promise<{ pending: PendingChat; revision: number }> {
    const id = targetId || "new",
      content = drafts.value[id] || "",
      selectedFiles = [...(files.value[id] || [])],
      selectedThinking = thinking.value[id] || "off",
      selectedModel = { ...models.value[id] },
      selectedSources = [...sourceIds],
      revision = draftRevisions.get(id) || 0,
      key = crypto.randomUUID();
    if ((!content.trim() && !selectedFiles.length) || content.length > 12000)
      throw new Error("请写一句想聊的话，最多12000字。");
    if (preparing.value) throw new Error("附件正在处理，请稍等。");
    const capturedEpoch = epoch;
    if (selectedFiles.length) {
      const originals = (
        await Promise.all(
          selectedFiles.map((f) =>
            getMedia(
              owner,
              f.id,
              () => validOwner() && capturedEpoch === epoch,
            ),
          ),
        )
      ).flat();
      checkFileLimits(originals);
      await putMedia(
        owner,
        key,
        originals,
        Date.now() + 30 * 86400000,
        () => validOwner() && capturedEpoch === epoch,
      );
    }
    if (!validOwner() || epoch !== capturedEpoch)
      throw new ApiError("会话已结束。", 499);
    return {
      revision,
      pending: {
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
          body: {
            content,
            ...(targetId ? { sourceIds: selectedSources } : {}),
            thinking: selectedThinking,
            ...selectedModel,
          },
        },
      },
    };
  }
  function consumeSnapshot(id: string, p: PendingChat, revision: number) {
    if ((draftRevisions.get(id) || 0) === revision) drafts.value[id] = "";
    const consumed = new Set((p.files || []).map((f) => f.id));
    files.value[id] = (files.value[id] || []).filter(
      (f) => !consumed.has(f.id),
    );
    save();
  }
  async function send(
    targetId: string | null,
    sourceIds: string[] = [],
    allowQueue = false,
  ) {
    const id = targetId || "new";
    setTarget(id);
    if (sends.has(id)) throw new Error("上一段话正在发送，请稍等。");
    if (pendings.value[id] && !allowQueue)
      throw new Error("上一段话还在处理中，请先恢复或重试回答。");
    sends.add(id);
    const sendingEpoch = epoch;
    let p: PendingChat;
    try {
      const captured = await snapshot(targetId, sourceIds);
      p = captured.pending;
      if (pendings.value[id] || queues.value[id]?.length) {
        if (!targetId) throw new Error("首条消息尚未创建对话，请稍后排队。");
        if ((queues.value[id] || []).length >= 20)
          throw new Error("本机会话队列最多20条。");
        p.state = "queued";
        (queues.value[id] ||= []).push(p);
        save();
        consumeSnapshot(id, p, captured.revision);
        return;
      }
      pendings.value[id] = p;
      save();
      consumeSnapshot(id, p, captured.revision);
      p = pendings.value[id];
    } finally {
      if (epoch === sendingEpoch) sends.delete(id);
    }
    return perform(p!);
  }
  async function resume(retryFailed = false, id = target.value) {
    const p = pendings.value[id];
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
      p.cursor = 0;
      save();
      return perform(p);
    }
    return observe(p, true);
  }
  async function cancel(id = target.value) {
    const p = pendings.value[id];
    if (!p?.job) return;
    const result = await request<Job>(`/jobs/${p.job.id}/cancel`, "POST", {});
    if (!active(p)) return;
    p.job = result;
    if (p.job.status === "succeeded") return finish(p);
    if (p.job.status === "cancelled") {
      p.state = "cancelled";
      pausedQueues.value[id] = true;
    }
    save();
  }
  function editOriginal() {
    const p = pending.value;
    if (!p || p.state !== "stale") return;
    drafts.value[target.value] = p.content;
    files.value[target.value] = p.files || [];
    thinking.value[target.value] = p.thinking || "off";
    delete pendings.value[target.value];
    save();
    if (p.mediaKey) release([p.mediaKey]);
  }
  function leaveFinished() {
    const p = pending.value;
    if (
      !p ||
      p.operation ||
      !p.job ||
      !["failed", "succeeded", "cancelled"].includes(p.job.status)
    )
      throw new Error("这段话还未确认处理结果，请先恢复发送。");
    delete pendings.value[target.value];
    save();
    release(mediaIds(p));
  }
  function leaveMissingMedia() {
    const p = pending.value;
    if (!p?.mediaUnavailable) return;
    delete pendings.value[target.value];
    save();
    release(mediaIds(p));
  }
  function cancelQueued(id: string, key: string) {
    const q = queues.value[id] || [];
    const removed = q.find((p) => p.operation?.key === key);
    queues.value[id] = q.filter((p) => p.operation?.key !== key);
    save();
    if (removed) release(mediaIds(removed));
  }
  async function drain(id: string) {
    if (
      !validOwner() ||
      pendings.value[id] ||
      pausedQueues.value[id] ||
      !queues.value[id]?.length
    )
      return;
    const p = queues.value[id].shift()!;
    p.state = "sending";
    pendings.value[id] = p;
    save();
    await perform(pendings.value[id]);
  }
  async function continueQueue(id = target.value) {
    const p = pendings.value[id];
    if (p?.job && ["failed", "cancelled"].includes(p.job.status)) {
      delete pendings.value[id];
      release(mediaIds(p));
    }
    pausedQueues.value[id] = false;
    save();
    await drain(id);
  }
  async function recover() {
    const captured = epoch;
    let cursor: string | null = null;
    do {
      const remote: { items: Job[]; nextCursor?: string | null } =
        await request(
          `/jobs?kind=chat&status=active&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        );
      if (!validOwner() || epoch !== captured) return;
      for (const job of remote.items) {
        if (job.problemId && !pendings.value[job.problemId])
          pendings.value[job.problemId] = {
            problemId: job.problemId,
            content: "",
            job,
            state: "waiting",
          };
      }
      cursor = remote.nextCursor || null;
    } while (cursor);
    save();
    for (const [id, p] of Object.entries(pendings.value))
      if (p.job && !p.operation && p.state !== "stale")
        void resume(false, id).catch(() => {});
  }
  return {
    pending,
    pendings,
    queues,
    pausedQueues,
    drafts,
    files,
    thinking,
    models,
    preparing,
    unread,
    setTarget,
    setModel,
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
    cancel,
    editOriginal,
    leaveFinished,
    leaveMissingMedia,
    cancelQueued,
    continueQueue,
    recover,
    dispose: () => {
      disposed = true;
    },
  };
}
