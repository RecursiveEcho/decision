import { ref } from "vue";
import { ApiError, list, request, sessionId, sleep } from "./api";
import type { Job, KnowledgeEntry, KnowledgeProposal } from "./types";
import {
  readKnowledgeOperations,
  saveKnowledgeOperations,
  type KnowledgeOperation,
} from "./knowledgeStorage";

export const knowledgeActions = {
  create: "新增",
  merge: "合并",
  revise: "修订 / 裁剪",
  skip: "暂不保存",
};

// Only explicit prepare/confirm/dismiss actions create writes. Navigation restores saved jobs
// using GET; an unknown POST result must reuse its original body and key on explicit reconnect.
export function useKnowledge() {
  const proposals = ref<KnowledgeProposal[]>([]);
  const suggestion = ref<KnowledgeProposal | null>(null);
  const suggestionLoading = ref(false);
  const suggestionError = ref("");
  let suggestionSequence = 0;
  const entries = ref<KnowledgeEntry[]>([]);
  const loading = ref(false);
  const working = ref(false);
  const error = ref("");
  const note = ref("");
  const uncertain = ref(false);
  const stale = ref(false);
  let generation = 0;
  let readSequence = 0;
  let problemId = "";
  let owner = "";
  let operations = new Map<string, KnowledgeOperation>();
  function stop() {
    generation++;
    suggestionSequence++;
    suggestion.value = null;
    suggestionLoading.value = false;
    suggestionError.value = "";
    proposals.value = [];
    entries.value = [];
    loading.value = false;
    working.value = false;
    error.value = "";
    note.value = "";
    uncertain.value = false;
    stale.value = false;
  }
  function scope() {
    const g = generation,
      p = problemId,
      who = owner;
    const active = () =>
      !!who && g === generation && p === problemId && who === sessionId();
    const save = () => {
      if (!active()) return;
      // Another conversation may have been archived while this one stayed open.
      const latest = readKnowledgeOperations(who);
      const operation = operations.get(p);
      if (operation) latest.set(p, operation);
      else latest.delete(p);
      saveKnowledgeOperations(who, latest);
    };
    const clear = () => {
      operations.delete(p);
      save();
      uncertain.value = false;
    };
    return { active, save, clear, p };
  }
  async function read(current: ReturnType<typeof scope>) {
    const sequence = ++readSequence;
    const [p, e] = await Promise.all([
      list<KnowledgeProposal>(
        `/knowledge-proposals?problemId=${encodeURIComponent(current.p)}`,
      ),
      list<KnowledgeEntry>("/knowledge-entries"),
    ]);
    if (!current.active() || sequence !== readSequence) return;
    proposals.value = p;
    entries.value = e;
    if (suggestion.value) {
      const previous = suggestion.value;
      const updated = p.find((item) => item.id === previous.id);
      if (
        updated &&
        (updated.status !== "pending" || previous.status === "pending")
      ) {
        suggestion.value = {
          ...updated,
          requiresRefresh: previous.requiresRefresh || updated.requiresRefresh,
        };
      }
    }
  }
  function failure(value: unknown, current: ReturnType<typeof scope>) {
    if (!current.active()) return;
    if (value instanceof ApiError && value.status === 499) return;
    if (value instanceof ApiError && value.code === "KNOWLEDGE_CHANGED") {
      current.clear();
      stale.value = true;
      error.value =
        "对话、出处或已有知识已经变化，请重新整理，再审阅最新建议。";
      return;
    }
    if (
      value instanceof ApiError &&
      [400, 404, 409, 413, 422].includes(value.status)
    )
      current.clear();
    error.value =
      value instanceof Error ? value.message : "暂时无法整理，请稍后再试。";
    uncertain.value = operations.has(current.p);
  }
  async function poll(
    current: ReturnType<typeof scope>,
    op: KnowledgeOperation,
  ) {
    if (!op.job) return;
    const deadline = Date.now() + 360000;
    let job = await request<Job>(`/jobs/${encodeURIComponent(op.job.id)}`);
    while (current.active()) {
      op.job = { id: job.id, status: job.status, mode: job.mode, error: null };
      current.save();
      if (job.status === "failed") {
        current.clear();
        throw new ApiError(
          job.error?.message || "这次整理未完成，原对话仍在，可以重新整理。",
          502,
          job.error?.code,
        );
      }
      if (job.status === "succeeded") {
        await read(current);
        if (current.active()) {
          current.clear();
          note.value = "建议已整理，审阅后再决定是否入库。";
        }
        return;
      }
      note.value = "AI 正在对照已有知识；尚未入库。";
      if (Date.now() >= deadline) {
        error.value = "还在整理，可以稍后重新连接查看。";
        uncertain.value = true;
        return;
      }
      await sleep(1500);
      if (!current.active()) return;
      job = await request<Job>(`/jobs/${encodeURIComponent(job.id)}`);
    }
  }
  async function execute(
    current: ReturnType<typeof scope>,
    op: KnowledgeOperation,
  ) {
    working.value = true;
    error.value = "";
    note.value = "";
    uncertain.value = false;
    stale.value = false;
    try {
      if (op.kind === "prepare") {
        if (!op.job) {
          const job = await request<Job>(
            `/problems/${encodeURIComponent(current.p)}/knowledge-proposals`,
            "POST",
            {},
            op.key,
          );
          if (!current.active()) return;
          op.job = {
            id: job.id,
            status: job.status,
            mode: job.mode,
            error: null,
          };
          current.save();
        }
        await poll(current, op);
      } else {
        await request(
          `/knowledge-proposals/${encodeURIComponent(op.proposalId!)}/${op.kind}`,
          "POST",
          op.kind === "accept"
            ? { expectedTargetRevision: op.expectedTargetRevision }
            : {},
          op.key,
        );
        if (!current.active()) return;
        current.clear();
        note.value =
          op.kind === "accept"
            ? "已确认入库。Obsidian 文件同步由本机服务单独完成。"
            : "已略过，原对话保留。";
        await read(current);
      }
    } catch (value) {
      failure(value, current);
      // Resolve a lost confirmation response by reading durable state, never by inventing a new key.
      if (current.active() && op.kind !== "prepare") {
        await read(current).catch(() => {});
        if (!current.active()) return;
        const saved = proposals.value.find((p) => p.id === op.proposalId);
        if (
          saved &&
          saved.status === (op.kind === "accept" ? "accepted" : "dismissed")
        ) {
          current.clear();
          error.value = "";
          note.value =
            saved.status === "accepted"
              ? "已确认入库。"
              : "已略过，原对话保留。";
        }
      }
    } finally {
      if (current.active()) working.value = false;
    }
  }
  async function open(id: string) {
    stop();
    problemId = id;
    owner = sessionId();
    operations = readKnowledgeOperations(owner);
    const current = scope();
    if (!id || !current.active()) return;
    loading.value = true;
    try {
      await read(current);
      if (!current.active()) return;
      const op = operations.get(id);
      if (op?.kind === "prepare" && op.job) await execute(current, op);
      else if (op) {
        const saved = proposals.value.find((p) => p.id === op.proposalId);
        if (
          saved &&
          saved.status === (op.kind === "accept" ? "accepted" : "dismissed")
        )
          current.clear();
        else {
          uncertain.value = true;
          error.value = "上次请求结果尚未确认，重新连接会继续同一次操作。";
        }
      }
    } catch (value) {
      failure(value, current);
    } finally {
      if (current.active()) loading.value = false;
    }
  }
  async function followSuggestion(jobId: string) {
    const sequence = ++suggestionSequence,
      current = scope();
    const active = () => current.active() && sequence === suggestionSequence;
    suggestion.value = null;
    suggestionError.value = "";
    suggestionLoading.value = !!jobId;
    if (!jobId || !active()) return;
    const deadline = Date.now() + 360000;
    try {
      while (active()) {
        const job = await request<Job & { problemId?: string }>(
          `/jobs/${encodeURIComponent(jobId)}`,
        );
        if (!active()) return;
        if (
          (job.problemId && job.problemId !== current.p) ||
          (job.kind && job.kind !== "knowledge_proposal")
        )
          return;
        if (job.status === "failed") {
          // A stale or failed draft never changes the successful chat response.
          suggestionError.value =
            "这次保存建议没有整理完成，可以继续聊或稍后重试。";
          return;
        }
        if (job.status === "succeeded") {
          const id = job.result?.proposalId;
          if (!id) return;
          const proposal = await request<KnowledgeProposal>(
            `/knowledge-proposals/${encodeURIComponent(id)}`,
          );
          if (!active()) return;
          if (proposal.problemId !== current.p || proposal.action === "skip")
            return;
          if (stale.value && !proposal.requiresRefresh) {
            stale.value = false;
            error.value = "";
          }
          suggestion.value = proposal;
          return;
        }
        if (Date.now() >= deadline) {
          suggestionError.value = "保存建议还在整理，可以稍后查看。";
          return;
        }
        await sleep(1500);
      }
    } catch (value) {
      if (active() && !(value instanceof ApiError && value.status === 499))
        suggestionError.value = "保存建议暂时无法读取，可以稍后重新连接。";
    } finally {
      if (active()) suggestionLoading.value = false;
    }
  }
  async function refresh() {
    const current = scope();
    if (!current.active() || working.value || loading.value) return;
    loading.value = true;
    try {
      await read(current);
    } catch (value) {
      if (current.active())
        error.value =
          value instanceof Error ? value.message : "保存建议暂时无法读取。";
    } finally {
      if (current.active()) loading.value = false;
    }
  }
  async function run(
    kind: KnowledgeOperation["kind"],
    proposal?: KnowledgeProposal,
  ) {
    const current = scope();
    if (kind === "accept" && proposal?.requiresRefresh) {
      stale.value = true;
      error.value = "这份建议已有变化，请根据最新讨论重新整理。";
      return;
    }
    if (
      !current.active() ||
      loading.value ||
      working.value ||
      operations.has(current.p)
    )
      return;
    const op: KnowledgeOperation = { kind, key: crypto.randomUUID() };
    if (proposal) {
      op.proposalId = proposal.id;
      if (kind === "accept")
        op.expectedTargetRevision = proposal.targetRevision;
    }
    try {
      operations.set(current.p, op);
      current.save(); // If storage fails, no POST is allowed: recovery identity would be lost.
    } catch (value) {
      operations.delete(current.p);
      failure(value, current);
      return;
    }
    await execute(current, op);
  }
  async function resume() {
    const current = scope(),
      op = operations.get(problemId);
    if (!current.active() || working.value || loading.value || !op) return;
    await execute(current, op);
  }
  return {
    proposals,
    suggestion,
    suggestionLoading,
    suggestionError,
    followSuggestion,
    entries,
    loading,
    working,
    error,
    note,
    uncertain,
    stale,
    open,
    stop,
    refresh,
    prepare: () => run("prepare"),
    accept: (p: KnowledgeProposal) => run("accept", p),
    dismiss: (p: KnowledgeProposal) => run("dismiss", p),
    resume,
  };
}
