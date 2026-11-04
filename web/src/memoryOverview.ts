import { ref } from "vue";
import { ApiError, request, sessionId } from "./api";
import type { Memory, MemoryOverview, MemoryOverviewItem } from "./types";
import {
  readOverviewOperations,
  saveOverviewOperations,
  type OverviewOperation,
} from "./overviewStorage";

// Only display a derived point while all of its exact source revisions remain valid.
export function checkedOverviewItems(
  overview: MemoryOverview | null,
  memories: Memory[],
): MemoryOverviewItem[] | null {
  if (!overview || overview.status !== "ready") return [];
  if (!Array.isArray(overview.items) || overview.items.length > 5) return null;
  const seen = new Set<string>();
  for (const item of overview.items) {
    if (
      !item.summary?.trim() ||
      Array.from(item.summary).length > 80 ||
      !item.memoryRefs?.length ||
      !["global", "problem"].includes(item.scope)
    )
      return null;
    if (item.scope === "global" ? item.problemId !== null : !item.problemId)
      return null;
    for (const reference of item.memoryRefs) {
      const memory = memories.find((value) => value.id === reference.id);
      if (
        !memory ||
        seen.has(reference.id) ||
        memory.revision !== reference.revision ||
        memory.status !== "active" ||
        memory.kind !== "user_reported" ||
        (memory.scope || "problem") !== item.scope ||
        (item.scope === "problem" && memory.problemId !== item.problemId)
      )
        return null;
      seen.add(reference.id);
    }
  }
  return overview.items;
}

export function useMemoryOverview() {
  const overview = ref<MemoryOverview | null>(null);
  const loading = ref(false);
  const error = ref("");
  const uncertain = ref(false);
  let generation = 0;
  let owner = "";
  // Unknown POST outcomes keep their original key across page navigation.
  let operations = new Map<string, OverviewOperation>();
  function stop() {
    generation++;
    overview.value = null;
    loading.value = false;
    error.value = "";
    uncertain.value = false;
  }
  async function open(options: { retry?: boolean; resume?: boolean } = {}) {
    if (loading.value && (options.retry || options.resume)) return;
    const currentOwner = sessionId();
    if (owner !== currentOwner) {
      operations = readOverviewOperations(currentOwner);
      owner = currentOwner;
    }
    const current = ++generation;
    const active = () =>
      !!currentOwner && current === generation && currentOwner === sessionId();
    const save = () => {
      if (active()) saveOverviewOperations(currentOwner, operations);
    };
    if (!active()) return;
    overview.value = null;
    error.value = "";
    uncertain.value = false;
    loading.value = true;
    const read = async () => {
      const value = await request<MemoryOverview>("/memories/overview");
      if (active()) overview.value = value;
      return value;
    };
    const post = async (
      value: MemoryOverview,
      retry: boolean,
      resume: boolean,
    ) => {
      let op = operations.get(value.fingerprint);
      if (!op || (retry && !op.unconfirmed)) {
        op = {
          key: crypto.randomUUID(),
          retry,
          previousJobId: value.jobId,
          unconfirmed: true,
        };
        operations.set(value.fingerprint, op);
      } else if (!resume) {
        uncertain.value = true;
        error.value = "上次整理请求尚未确认，可以重新连接查看。";
        return false;
      }
      save();
      try {
        await request(
          "/memories/overview",
          "POST",
          {
            fingerprint: value.fingerprint,
            ...(op.retry ? { retry: true } : {}),
          },
          op.key,
        );
        if (active()) {
          op.unconfirmed = false;
          save();
        }
        return true;
      } catch (failure) {
        if (!active()) return false;
        if (
          failure instanceof ApiError &&
          failure.code === "OVERVIEW_CHANGED"
        ) {
          operations.delete(value.fingerprint);
          save();
          return true;
        }
        uncertain.value = true;
        error.value =
          failure instanceof Error
            ? failure.message
            : "暂时无法连接，原记录仍可查看。";
        // A lost response may already have created a job. Confirm via GET, never a new POST key.
        return true;
      }
    };
    try {
      let value = await read();
      if (!active()) return;
      if (
        value.status === "missing" ||
        (options.resume &&
          value.status === "failed" &&
          operations.get(value.fingerprint)?.unconfirmed) ||
        (options.retry && value.status === "failed" && value.error?.retryable)
      ) {
        const sent = await post(
          value,
          !!options.retry && value.status === "failed",
          !!options.resume,
        );
        if (!sent || !active()) return;
        value = await read();
        if (!active()) return;
      }
      const deadline = Date.now() + 120000;
      while (value.status === "pending" && active()) {
        const op = operations.get(value.fingerprint);
        if (op) {
          op.unconfirmed = false;
          save();
        }
        error.value = "";
        uncertain.value = false;
        if (Date.now() >= deadline) {
          error.value = "还在整理，可以稍后重新查看。";
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 1500));
        if (!active()) return;
        value = await read();
      }
      if (!active()) return;
      if (["ready", "empty", "failed"].includes(value.status)) {
        const op = operations.get(value.fingerprint);
        if (
          value.status === "failed" &&
          op?.unconfirmed &&
          value.jobId === op.previousJobId
        ) {
          uncertain.value = true;
          error.value = "上次重试结果尚未确认，可以重新连接查看。";
          return;
        }
        operations.delete(value.fingerprint);
        save();
        uncertain.value = false;
        error.value =
          value.status === "failed"
            ? value.error?.message || "这次未能整理重点。"
            : "";
      } else if (value.status === "missing" && !error.value) {
        error.value = "记忆刚有变化，请重新查看最新重点。";
      }
    } catch (failure) {
      if (active())
        error.value =
          failure instanceof Error
            ? failure.message
            : "重点暂时无法读取，原记录仍可查看。";
    } finally {
      if (active()) loading.value = false;
    }
  }
  return { overview, loading, error, uncertain, open, stop };
}
