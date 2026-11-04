import { beforeEach, afterEach, expect, it, vi } from "vitest";
import type { Memory, MemoryOverview } from "../src/types";
const api = vi.hoisted(() => ({ request: vi.fn(), owner: "owner-a" }));
vi.mock("../src/api", () => ({
  request: api.request,
  sessionId: () => api.owner,
  ApiError: class extends Error {
    constructor(
      message: string,
      public status = 0,
      public code = "",
    ) {
      super(message);
    }
  },
}));
import { ApiError } from "../src/api";
import { checkedOverviewItems, useMemoryOverview } from "../src/memoryOverview";
const state = (
  status: MemoryOverview["status"],
  extra: Partial<MemoryOverview> = {},
): MemoryOverview => ({
  status,
  fingerprint: "fp-a",
  items: [],
  jobId: status === "failed" ? "old-failure" : null,
  error:
    status === "failed"
      ? { code: "MODEL", message: "合成模型错误", retryable: true }
      : null,
  coverage: { eligibleCount: 2, consideredCount: 2, truncated: false },
  ...extra,
});
beforeEach(() => {
  vi.useFakeTimers();
  api.owner = "owner-a";
  api.request.mockReset();
  const store = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    getItem: (key: string) => store.get(key) || null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => store.delete(key),
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const writes = () =>
  api.request.mock.calls.filter((call) => call[1] === "POST");

it("empty and cached ready are pure reads, including repeated visits", async () => {
  const flow = useMemoryOverview();
  api.request
    .mockResolvedValueOnce(state("empty"))
    .mockResolvedValue(state("ready"));
  await flow.open();
  expect(flow.overview.value?.status).toBe("empty");
  flow.stop();
  await flow.open();
  await flow.open();
  expect(writes()).toHaveLength(0);
});
it("missing creates once, polls overview and releases loading without touching chat jobs", async () => {
  api.request
    .mockResolvedValueOnce(state("missing"))
    .mockResolvedValueOnce({ status: "pending", jobId: "j1" })
    .mockResolvedValueOnce(state("pending", { jobId: "j1" }))
    .mockResolvedValueOnce(state("ready", { jobId: "j1" }));
  const flow = useMemoryOverview();
  const pending = flow.open();
  await vi.advanceTimersByTimeAsync(1500);
  await pending;
  expect(writes()).toHaveLength(1);
  expect(flow.overview.value?.status).toBe("ready");
  expect(flow.loading.value).toBe(false);
  expect(
    api.request.mock.calls.every((call) => call[0] === "/memories/overview"),
  ).toBe(true);
});
it("a navigation stop rejects a late result and stops polling", async () => {
  let finish!: (value: MemoryOverview) => void;
  api.request.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const flow = useMemoryOverview();
  const pending = flow.open();
  flow.stop();
  finish(state("ready"));
  await pending;
  expect(flow.overview.value).toBeNull();
  expect(flow.loading.value).toBe(false);
  expect(writes()).toHaveLength(0);
});
it("a response cannot cross a changed login or overwrite a newer load", async () => {
  let finish!: (value: MemoryOverview) => void;
  api.request
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValue(state("empty", { fingerprint: "new" }));
  const flow = useMemoryOverview();
  const old = flow.open();
  api.owner = "owner-b";
  await flow.open();
  finish(state("ready"));
  await old;
  expect(flow.overview.value?.fingerprint).toBe("new");
});
it("unknown initial POST does not auto repeat; reloading and explicit reconnect reuse its exact key", async () => {
  api.request
    .mockResolvedValueOnce(state("missing"))
    .mockRejectedValueOnce(new Error("网络未知"))
    .mockResolvedValue(state("missing"));
  const flow = useMemoryOverview();
  await flow.open();
  const original = writes()[0];
  flow.stop();
  await flow.open();
  expect(writes()).toHaveLength(1);
  const reloaded = useMemoryOverview();
  await reloaded.open({ resume: true });
  expect(writes()).toHaveLength(2);
  expect(writes()[1].slice(2)).toEqual(original.slice(2));
});
it("unknown retry followed by the original failed job preserves key/body, even across reload", async () => {
  api.request
    .mockResolvedValueOnce(state("failed"))
    .mockRejectedValueOnce(new ApiError("rate limit", 429))
    .mockResolvedValue(state("failed"));
  const flow = useMemoryOverview();
  await flow.open({ retry: true });
  const original = writes()[0];
  expect(flow.uncertain.value).toBe(true);
  expect(original[2]).toEqual({ fingerprint: "fp-a", retry: true });
  const reload = useMemoryOverview();
  await reload.open();
  expect(reload.uncertain.value).toBe(true);
  expect(writes()).toHaveLength(1);
  api.request
    .mockResolvedValueOnce(state("failed"))
    .mockResolvedValueOnce({ status: "pending" })
    .mockResolvedValueOnce(state("ready", { jobId: "new-job" }));
  await reload.open({ resume: true });
  expect(writes()[1].slice(2)).toEqual(original.slice(2));
  expect(reload.overview.value?.status).toBe("ready");
});
it("confirmed new failure permits a new explicit retry, but failed GET alone never creates", async () => {
  api.request.mockResolvedValue(state("failed"));
  const flow = useMemoryOverview();
  await flow.open();
  expect(writes()).toHaveLength(0);
  api.request
    .mockResolvedValueOnce(state("failed"))
    .mockResolvedValueOnce({ status: "pending" })
    .mockResolvedValueOnce(state("failed", { jobId: "new-failed" }));
  await flow.open({ retry: true });
  const original = writes()[0][3];
  api.request
    .mockResolvedValueOnce(state("failed", { jobId: "new-failed" }))
    .mockResolvedValueOnce({ status: "pending" })
    .mockResolvedValueOnce(state("ready"));
  await flow.open({ retry: true });
  expect(writes()[1][3]).not.toBe(original);
});
it("fingerprint conflict discards the old view and reads current state without replaying old content", async () => {
  api.request
    .mockResolvedValueOnce(state("missing"))
    .mockRejectedValueOnce(new ApiError("changed", 409, "OVERVIEW_CHANGED"))
    .mockResolvedValueOnce(state("ready", { fingerprint: "new-fp" }));
  const flow = useMemoryOverview();
  await flow.open();
  expect(flow.overview.value?.fingerprint).toBe("new-fp");
  expect(writes()).toHaveLength(1);
});
it("nonretryable failures and blocked session storage never cause another model request", async () => {
  api.request.mockResolvedValue(
    state("failed", {
      error: { code: "INVALID", message: "不能重试", retryable: false },
    }),
  );
  const flow = useMemoryOverview();
  await flow.open({ retry: true });
  expect(writes()).toHaveLength(0);
  api.request.mockResolvedValue(state("missing"));
  vi.spyOn(sessionStorage, "setItem").mockImplementation(() => {
    throw new Error("quota");
  });
  await flow.open();
  expect(writes()).toHaveLength(0);
  expect(flow.error.value).toBe("quota");
});

const memory = (id: string, extra: Partial<Memory> = {}): Memory => ({
  id,
  problemId: "p1",
  scope: "global",
  kind: "user_reported",
  status: "active",
  revision: 1,
  content: "偏好合成测试",
  evidence: [],
  ...extra,
});
it("shows supplied semantic summary and exact refs, never truncates raw records into highlights", () => {
  const memories = [memory("a"), memory("b")];
  const overview = state("ready", {
    items: [
      {
        summary: "偏好先休息再阅读。",
        scope: "global",
        problemId: null,
        memoryRefs: [
          { id: "a", revision: 1 },
          { id: "b", revision: 1 },
        ],
      },
    ],
  });
  expect(checkedOverviewItems(overview, memories)).toEqual(overview.items);
  expect(
    checkedOverviewItems(overview, [memory("a", { revision: 2 }), memory("b")]),
  ).toBeNull();
  expect(
    checkedOverviewItems(overview, [
      memory("a", { status: "retracted" }),
      memory("b"),
    ]),
  ).toBeNull();
  expect(
    checkedOverviewItems(overview, [
      memory("a", { kind: "inferred" }),
      memory("b"),
    ]),
  ).toBeNull();
  expect(
    checkedOverviewItems(overview, [
      memory("a", { scope: "problem" }),
      memory("b"),
    ]),
  ).toBeNull();
});
it("rejects oversized, duplicate and cross-problem projection references instead of faking five points", () => {
  const point = {
    summary: "合成重点",
    scope: "problem" as const,
    problemId: "p1",
    memoryRefs: [{ id: "a", revision: 1 }],
  };
  const memories = [memory("a", { scope: "problem" })];
  expect(
    checkedOverviewItems(state("ready", { items: [point, point] }), memories),
  ).toBeNull();
  expect(
    checkedOverviewItems(
      state("ready", { items: [{ ...point, problemId: "p2" }] }),
      memories,
    ),
  ).toBeNull();
  expect(
    checkedOverviewItems(
      state("ready", { items: [{ ...point, summary: "长".repeat(81) }] }),
      memories,
    ),
  ).toBeNull();
  expect(
    checkedOverviewItems(
      state("ready", { items: Array(6).fill(point) }),
      memories,
    ),
  ).toBeNull();
});
