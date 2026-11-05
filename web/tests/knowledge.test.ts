import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { Job, KnowledgeProposal } from "../src/types";
const api = vi.hoisted(() => ({
  request: vi.fn(),
  list: vi.fn(),
  owner: "owner-a",
}));
vi.mock("../src/api", () => ({
  request: api.request,
  list: api.list,
  sessionId: () => api.owner,
  sleep: (ms: number) => new Promise((resolve) => setTimeout(resolve, ms)),
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
import { useKnowledge } from "../src/knowledge";
const proposal = (
  extra: Partial<KnowledgeProposal> = {},
): KnowledgeProposal => ({
  id: "proposal-a",
  problemId: "problem-a",
  status: "pending",
  action: "create",
  title: "合成知识",
  markdown: "合成讨论结论",
  domains: ["合成领域"],
  relatedEntryIds: [],
  refs: [],
  reason: "合成测试",
  targetEntryId: null,
  targetRevision: null,
  contextFingerprint: "snapshot",
  createdAt: "2026-11-05T00:00:00Z",
  updatedAt: "2026-11-05T00:00:00Z",
  coverage: {
    messagesTotal: 2,
    messagesConsidered: 2,
    sourcesTotal: 0,
    sourcesConsidered: 0,
    knowledgeTotal: 0,
    knowledgeConsidered: 0,
    truncated: false,
    attachmentsIncluded: false,
    notice: "合成覆盖",
  },
  ...extra,
});
const job = (status: string): Job => ({
  id: "job-a",
  status,
  mode: "mock",
  error: null,
  result: status === "succeeded" ? { proposalId: "proposal-a" } : null,
});
const writes = () =>
  api.request.mock.calls.filter((call) => call[1] === "POST");
let savedProposals: KnowledgeProposal[];
beforeEach(() => {
  vi.useFakeTimers();
  api.owner = "owner-a";
  api.request.mockReset();
  api.list.mockReset();
  savedProposals = [];
  api.list.mockImplementation(async (path: string) =>
    path.startsWith("/knowledge-proposals") ? savedProposals : [],
  );
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

it("reading pending and accepted proposals never confirms or generates on its own", async () => {
  savedProposals = [proposal()];
  const flow = useKnowledge();
  await flow.open("problem-a");
  expect(flow.proposals.value).toEqual(savedProposals);
  savedProposals = [proposal({ status: "accepted", entryId: "entry-a" })];
  await flow.open("problem-a");
  expect(flow.proposals.value[0].status).toBe("accepted");
  expect(writes()).toHaveLength(0);
});
it("preparing polls its durable job and only produces a proposal without accepting", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request.mockImplementation(async (path: string, method?: string) => {
    if (method === "POST") return job("queued");
    savedProposals = [proposal()];
    return job("succeeded");
  });
  await flow.prepare();
  expect(writes()).toHaveLength(1);
  expect(writes()[0][0]).toBe("/problems/problem-a/knowledge-proposals");
  expect(flow.proposals.value[0].status).toBe("pending");
  expect(flow.working.value).toBe(false);
});
it("a lost prepare response survives reload and reconnects with the exact original key", async () => {
  const first = useKnowledge();
  await first.open("problem-a");
  api.request.mockRejectedValueOnce(new Error("合成断线"));
  await first.prepare();
  const original = writes()[0];
  expect(first.uncertain.value).toBe(true);
  first.stop();
  const reload = useKnowledge();
  await reload.open("problem-a");
  expect(writes()).toHaveLength(1);
  expect(reload.uncertain.value).toBe(true);
  api.request
    .mockResolvedValueOnce(job("queued"))
    .mockResolvedValueOnce(job("succeeded"));
  await reload.resume();
  expect(writes()).toHaveLength(2);
  expect(writes()[1]).toEqual(original);
  expect(reload.uncertain.value).toBe(false);
});
it("reloading a known job resumes using GET without generating another model task", async () => {
  const first = useKnowledge();
  await first.open("problem-a");
  api.request
    .mockResolvedValueOnce(job("queued"))
    .mockRejectedValueOnce(new Error("合成断线"));
  await first.prepare();
  first.stop();
  api.request.mockResolvedValueOnce(job("succeeded"));
  const reload = useKnowledge();
  await reload.open("problem-a");
  expect(writes()).toHaveLength(1);
  expect(reload.uncertain.value).toBe(false);
});
it("confirm submits only the saved proposal identity and target revision, and never client text", async () => {
  const p = proposal({
    action: "merge",
    targetEntryId: "entry-a",
    targetRevision: 4,
  });
  savedProposals = [p];
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request.mockImplementation(async () => {
    savedProposals = [
      proposal({ ...p, status: "accepted", entryId: "entry-a" }),
    ];
    return { proposalId: p.id, entryId: "entry-a", status: "accepted" };
  });
  await flow.accept(p);
  expect(writes()).toHaveLength(1);
  expect(writes()[0].slice(0, 3)).toEqual([
    "/knowledge-proposals/proposal-a/accept",
    "POST",
    { expectedTargetRevision: 4 },
  ]);
  expect(flow.proposals.value[0].status).toBe("accepted");
});
it("lost confirmation response is resolved from the accepted record without a second write", async () => {
  savedProposals = [proposal()];
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request.mockImplementation(async () => {
    savedProposals = [proposal({ status: "accepted", entryId: "entry-a" })];
    throw new Error("合成响应丢失");
  });
  await flow.accept(proposal());
  expect(writes()).toHaveLength(1);
  expect(flow.error.value).toBe("");
  expect(flow.uncertain.value).toBe(false);
  expect(flow.proposals.value[0].status).toBe("accepted");
});
it("unknown acceptance keeps the original revision and key across navigation", async () => {
  const p = proposal({
    action: "revise",
    targetEntryId: "entry-a",
    targetRevision: 3,
  });
  savedProposals = [p];
  const first = useKnowledge();
  await first.open("problem-a");
  api.request.mockRejectedValueOnce(new Error("合成断线"));
  await first.accept(p);
  const original = writes()[0];
  const reload = useKnowledge();
  await reload.open("problem-a");
  await reload.prepare();
  expect(writes()).toHaveLength(1);
  api.request.mockResolvedValueOnce({ status: "accepted" });
  await reload.resume();
  expect(writes()[1]).toEqual(original);
});
it("stale version rejection retains preview and enables explicit fresh preparation", async () => {
  savedProposals = [proposal()];
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request.mockRejectedValueOnce(
    new ApiError("合成冲突", 409, "KNOWLEDGE_CHANGED"),
  );
  await flow.accept(proposal());
  expect(flow.stale.value).toBe(true);
  expect(flow.uncertain.value).toBe(false);
  expect(flow.proposals.value[0].title).toBe("合成知识");
  api.request
    .mockResolvedValueOnce(job("queued"))
    .mockResolvedValueOnce(job("succeeded"));
  await flow.prepare();
  expect(writes()).toHaveLength(2);
  expect(writes()[1][0]).toBe("/problems/problem-a/knowledge-proposals");
});
it("job failure does not auto retry and the user can explicitly prepare again", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request.mockResolvedValueOnce(job("queued")).mockResolvedValueOnce({
    ...job("failed"),
    error: { message: "合成失败" },
  });
  await flow.prepare();
  expect(flow.error.value).toBe("合成失败");
  expect(flow.uncertain.value).toBe(false);
  expect(writes()).toHaveLength(1);
  api.request
    .mockResolvedValueOnce(job("queued"))
    .mockResolvedValueOnce(job("succeeded"));
  await flow.prepare();
  expect(writes()).toHaveLength(2);
  expect(writes()[1][3]).not.toBe(writes()[0][3]);
});
it("late jobs cannot cross conversation navigation or changed login", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  let finish!: (value: Job) => void;
  api.request.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const pending = flow.prepare();
  await flow.open("problem-b");
  finish(job("queued"));
  await pending;
  expect(flow.proposals.value).toEqual([]);
  expect(api.request.mock.calls.some((call) => call[0] === "/jobs/job-a")).toBe(
    false,
  );
  const other = useKnowledge();
  api.owner = "owner-b";
  await other.open("problem-a");
  expect(other.uncertain.value).toBe(false);
  expect(writes()).toHaveLength(1);
});
it("storage failure blocks POST rather than losing the recovery identity", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  vi.stubGlobal("sessionStorage", {
    getItem: () => null,
    setItem: () => {
      throw new Error("合成存储不可用");
    },
  });
  await flow.prepare();
  expect(writes()).toHaveLength(0);
  expect(flow.error.value).toBe("合成存储不可用");
});

it("follows a persisted chat suggestion using only GET, then confirms the exact draft once", async () => {
  const p = proposal();
  savedProposals = [p];
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request
    .mockResolvedValueOnce({ ...job("queued"), kind: "knowledge_proposal" })
    .mockResolvedValueOnce({ ...job("succeeded"), kind: "knowledge_proposal" })
    .mockResolvedValueOnce(p);
  const following = flow.followSuggestion("suggestion-job");
  await vi.advanceTimersByTimeAsync(1500);
  await following;
  expect(flow.suggestion.value?.id).toBe(p.id);
  expect(writes()).toHaveLength(0);
  expect(api.request.mock.calls.map((call) => call[0])).toEqual([
    "/jobs/suggestion-job",
    "/jobs/suggestion-job",
    "/knowledge-proposals/proposal-a",
  ]);
  api.request.mockImplementation(async () => {
    savedProposals = [proposal({ status: "accepted", entryId: "entry-a" })];
    return { status: "accepted" };
  });
  await flow.accept(p);
  expect(writes()).toHaveLength(1);
  expect(flow.suggestion.value?.status).toBe("accepted");
});
it("does not display skipped drafts, mismatched conversations, or failed suggestions", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  for (const p of [
    proposal({ action: "skip" }),
    proposal({ problemId: "problem-b" }),
  ]) {
    api.request
      .mockResolvedValueOnce(job("succeeded"))
      .mockResolvedValueOnce(p);
    await flow.followSuggestion("suggestion-job");
    expect(flow.suggestion.value).toBeNull();
  }
  api.request.mockResolvedValueOnce(job("failed"));
  await flow.followSuggestion("suggestion-job");
  expect(flow.suggestion.value).toBeNull();
  expect(flow.suggestionError.value).toContain("没有整理完成");
  expect(writes()).toHaveLength(0);
});
it("a newer reply cancels an old suggestion's late result without clearing its own suggestion", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  let finish!: (value: Job) => void;
  api.request.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const old = flow.followSuggestion("old-job");
  api.request
    .mockResolvedValueOnce(job("succeeded"))
    .mockResolvedValueOnce(proposal({ id: "new-proposal" }));
  await flow.followSuggestion("new-job");
  finish(job("succeeded"));
  await old;
  expect(flow.suggestion.value?.id).toBe("new-proposal");
  await flow.followSuggestion("");
  expect(flow.suggestion.value).toBeNull();
  expect(writes()).toHaveLength(0);
});
it("conversation navigation, logout and a restarted controller cannot revive old suggestion reads", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  let finish!: (value: KnowledgeProposal) => void;
  api.request.mockResolvedValueOnce(job("succeeded")).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const old = flow.followSuggestion("old-job");
  await vi.advanceTimersByTimeAsync(0);
  flow.stop();
  api.owner = "owner-b";
  finish(proposal());
  await old;
  expect(flow.suggestion.value).toBeNull();
  expect(writes()).toHaveLength(0);
});
it("preserves server requiresRefresh on an expired draft without preparing or accepting it", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  api.request
    .mockResolvedValueOnce(job("succeeded"))
    .mockResolvedValueOnce(proposal({ requiresRefresh: true }));
  await flow.followSuggestion("suggestion-job");
  expect(flow.suggestion.value?.requiresRefresh).toBe(true);
  expect(writes()).toHaveLength(0);
});

it("rejects a server-marked stale draft locally and allows a fresh chat-bound replacement", async () => {
  const flow = useKnowledge();
  await flow.open("problem-a");
  await flow.accept(proposal({ requiresRefresh: true }));
  expect(writes()).toHaveLength(0);
  expect(flow.stale.value).toBe(true);
  api.request
    .mockResolvedValueOnce(job("succeeded"))
    .mockResolvedValueOnce(proposal({ id: "fresh", requiresRefresh: false }));
  await flow.followSuggestion("fresh-job");
  expect(flow.stale.value).toBe(false);
  expect(flow.error.value).toBe("");
  expect(flow.suggestion.value?.id).toBe("fresh");
});
