import { beforeEach, expect, it, vi } from "vitest";
import type { KnowledgeEntry, Source, Topic } from "../src/types";
const api = vi.hoisted(() => ({
  request: vi.fn(),
  list: vi.fn(),
  owner: "owner-a",
}));
vi.mock("../src/api", () => ({
  request: api.request,
  list: api.list,
  sessionId: () => api.owner,
}));
import { filterLibrary, libraryRows, useLibraryReader } from "../src/library";
const source = (id: string, text = "合成来源正文"): Source => ({
  id,
  problemId: "p-source",
  title: "缓存文章",
  text,
  kind: "article",
  originUrl: null,
  status: "ready",
  capturedAt: "2026-11-05T00:00:00Z",
});
const entry = (id: string): KnowledgeEntry => ({
  id,
  problemId: "p-knowledge",
  title: "缓存条件",
  markdown: "单机配置30秒，多机按版本号。",
  domains: ["配置管理"],
  relatedEntryIds: [],
  refs: [],
  revision: 3,
  proposalId: "proposal",
  createdAt: "2026-11-05T00:00:00Z",
  updatedAt: "2026-11-05T00:00:00Z",
  coverage: {
    messagesTotal: 2,
    messagesConsidered: 2,
    sourcesTotal: 0,
    sourcesConsidered: 0,
    knowledgeTotal: 1,
    knowledgeConsidered: 1,
    truncated: false,
    attachmentsIncluded: false,
    notice: "合成范围",
  },
});
const topic: Topic = {
  id: "topic",
  problemId: "p-source",
  title: "自动主题",
  markdown: "# 自动摘要",
  syncStatus: "failed",
  wikiUrl: null,
  evidence: [],
};
beforeEach(() => {
  api.request.mockReset();
  api.list.mockReset();
  api.owner = "owner-a";
});

it("searches full loaded content beyond the row excerpt without promoting sources or themes to knowledge", () => {
  const rows = libraryRows(
    [source("s", "正文".repeat(100) + "深处关键词")],
    [entry("k")],
    [topic],
  );
  expect(filterLibrary(rows, "source", "深处关键词")).toHaveLength(1);
  expect(filterLibrary(rows, "knowledge", "")).toHaveLength(1);
  expect(filterLibrary(rows, "source", "")[0].status).toBe("ready");
  expect(filterLibrary(rows, "topic", "")[0].status).toBe("failed");
  expect(filterLibrary(rows, "knowledge", "")[0].status).toBe("confirmed");
});
it("combines case-insensitive search and domain filters while keeping counts by content kind", () => {
  const e = entry("k");
  e.markdown = "API配置30秒";
  const rows = libraryRows([source("s")], [e], [topic]);
  expect(filterLibrary(rows, "knowledge", "api 30", "配置管理")).toHaveLength(
    1,
  );
  expect(filterLibrary(rows, "knowledge", "api", "其他")).toHaveLength(0);
  expect(filterLibrary(rows, "source", "api")).toHaveLength(0);
});
it("late index loading preserves the source the user selected during that loading", async () => {
  let finish!: (v: KnowledgeEntry[]) => void;
  api.list.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  api.request.mockResolvedValue(source("selected"));
  const reader = useLibraryReader();
  const loading = reader.load();
  await reader.open("source", "selected");
  finish([entry("k")]);
  await loading;
  expect(reader.source.value?.id).toBe("selected");
  expect(reader.entries.value).toHaveLength(1);
});
it("refreshing knowledge does not clear an already selected source or cancel its pending read", async () => {
  let finish!: (v: Source) => void;
  api.request.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  api.list.mockResolvedValue([]);
  const reader = useLibraryReader();
  const opening = reader.open("source", "selected");
  await reader.load();
  finish(source("selected"));
  await opening;
  expect(reader.source.value?.id).toBe("selected");
  await reader.load();
  expect(reader.source.value?.id).toBe("selected");
});
it("an older source response cannot overwrite the newer selected source", async () => {
  let finish!: (v: Source) => void;
  api.request
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce(source("new"));
  const reader = useLibraryReader();
  const old = reader.open("source", "old");
  await reader.open("source", "new");
  finish(source("old"));
  await old;
  expect(reader.source.value?.id).toBe("new");
});
it("a late knowledge body/history cannot cross source selection", async () => {
  let finish!: (v: KnowledgeEntry) => void;
  api.request
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce({ items: [entry("old")] })
    .mockResolvedValueOnce(source("s"));
  const reader = useLibraryReader();
  const old = reader.open("knowledge", "k");
  await reader.open("source", "s");
  finish(entry("k"));
  await old;
  expect(reader.source.value?.id).toBe("s");
  expect(reader.knowledge.value).toBeNull();
  expect(reader.history.value).toEqual([]);
});
it("knowledge-index failure stays separate from an otherwise readable source", async () => {
  api.list.mockRejectedValue(new Error("合成索引失败"));
  api.request.mockResolvedValue(source("s"));
  const reader = useLibraryReader();
  await reader.load();
  await reader.open("source", "s");
  expect(reader.knowledgeError.value).toBe("合成索引失败");
  expect(reader.error.value).toBe("");
  expect(reader.source.value?.id).toBe("s");
});
it("navigation or a changed login discards late results", async () => {
  let finish!: (v: Source) => void;
  api.request.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const reader = useLibraryReader();
  const old = reader.open("source", "s");
  reader.stop();
  finish(source("s"));
  await old;
  expect(reader.source.value).toBeNull();
  const changed = reader.open("source", "s");
  api.owner = "owner-b";
  finish(source("s"));
  await changed;
  expect(reader.source.value).toBeNull();
});
it("older knowledge refresh cannot override a newer refresh", async () => {
  let finish!: (v: KnowledgeEntry[]) => void;
  api.list
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce([entry("new")]);
  const reader = useLibraryReader();
  const old = reader.load();
  await reader.load();
  finish([entry("old")]);
  await old;
  expect(reader.entries.value[0].id).toBe("new");
});
it("all library navigation is GET only, including current knowledge and history", async () => {
  api.list.mockResolvedValue([entry("k")]);
  api.request
    .mockResolvedValueOnce(source("s"))
    .mockResolvedValueOnce(entry("k"))
    .mockResolvedValueOnce({ items: [] });
  const reader = useLibraryReader();
  await reader.load();
  await reader.open("source", "s");
  await reader.open("knowledge", "k");
  await reader.open("topic", "topic");
  expect(api.request.mock.calls.every((call) => call.length === 1)).toBe(true);
  expect(api.list.mock.calls).toEqual([["/knowledge-entries"]]);
});
