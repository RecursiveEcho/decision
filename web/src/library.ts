import { ref } from "vue";
import { list, request, sessionId } from "./api";
import type { KnowledgeEntry, Source, Topic } from "./types";

export type LibraryKind = "source" | "knowledge" | "topic";
export interface LibraryRow {
  kind: LibraryKind;
  id: string;
  title: string;
  preview: string;
  searchText: string;
  problemId: string;
  date: string;
  status: string;
  domains: string[];
}
export const libraryKinds: { value: LibraryKind; label: string }[] = [
  { value: "source", label: "资料" },
  { value: "knowledge", label: "已确认知识" },
  { value: "topic", label: "主题" },
];
export function plainExcerpt(content: string, limit = 108) {
  return content
    .replace(/[#>*_`]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, limit);
}
export function libraryRows(
  sources: Source[],
  entries: KnowledgeEntry[],
  topics: Topic[],
): LibraryRow[] {
  return [
    ...sources.map((s) => ({
      kind: "source" as const,
      id: s.id,
      title: s.title || "未命名资料",
      preview: plainExcerpt(s.text),
      searchText: s.text,
      problemId: s.problemId,
      date: s.capturedAt,
      status: s.status,
      domains: [],
    })),
    ...entries.map((e) => ({
      kind: "knowledge" as const,
      id: e.id,
      title: e.title,
      preview: plainExcerpt(e.markdown),
      searchText: e.markdown,
      problemId: e.problemId,
      date: e.updatedAt,
      status: "confirmed",
      domains: e.domains,
    })),
    ...topics.map((t) => ({
      kind: "topic" as const,
      id: t.id,
      title: t.title,
      preview: plainExcerpt(t.markdown),
      searchText: t.markdown,
      problemId: t.problemId,
      date: t.updatedAt || "",
      status: t.syncStatus,
      domains: [],
    })),
  ];
}
export function filterLibrary(
  rows: LibraryRow[],
  kind: LibraryKind,
  query: string,
  domain = "",
) {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return rows.filter(
    (row) =>
      row.kind === kind &&
      (!domain || row.domains.includes(domain)) &&
      words.every((word) =>
        `${row.title}\n${row.searchText}\n${row.domains.join(" ")}`
          .toLocaleLowerCase()
          .includes(word),
      ),
  );
}

// Reading and navigation are GET-only. Exact selection/session guards stop old responses
// replacing a newer article, including when navigating away during a slow source read.
export function useLibraryReader() {
  const entries = ref<KnowledgeEntry[]>([]),
    source = ref<Source | null>(null),
    knowledge = ref<KnowledgeEntry | null>(null),
    history = ref<KnowledgeEntry[]>([]);
  const loading = ref(false),
    reading = ref(false),
    error = ref(""),
    knowledgeError = ref("");
  let generation = 0,
    selection = 0,
    loadRequest = 0;
  let selectedKind: LibraryKind | null = null,
    selectedId = "";
  const active = (g: number, owner: string, s?: number) =>
    g === generation &&
    !!owner &&
    owner === sessionId() &&
    (s === undefined || s === selection);
  async function load() {
    const g = generation,
      owner = sessionId(),
      requestId = ++loadRequest;
    loading.value = true;
    knowledgeError.value = "";
    try {
      const values = await list<KnowledgeEntry>("/knowledge-entries");
      if (active(g, owner) && requestId === loadRequest) entries.value = values;
    } catch (value) {
      if (active(g, owner) && requestId === loadRequest)
        knowledgeError.value =
          value instanceof Error
            ? value.message
            : "知识暂时无法读取，请刷新重试。";
    } finally {
      if (active(g, owner) && requestId === loadRequest) loading.value = false;
    }
  }
  async function open(kind: LibraryKind, id: string) {
    selectedKind = kind;
    selectedId = id;
    const g = generation,
      s = ++selection,
      owner = sessionId();
    source.value = null;
    knowledge.value = null;
    history.value = [];
    error.value = "";
    if (kind === "topic") {
      reading.value = false;
      return;
    }
    reading.value = true;
    try {
      if (kind === "source") {
        const value = await request<Source>(
          `/sources/${encodeURIComponent(id)}`,
        );
        if (active(g, owner, s)) source.value = value;
      } else {
        const [value, previous] = await Promise.all([
          request<KnowledgeEntry>(
            `/knowledge-entries/${encodeURIComponent(id)}`,
          ),
          request<{ items: KnowledgeEntry[] }>(
            `/knowledge-entries/${encodeURIComponent(id)}/history`,
          ),
        ]);
        if (active(g, owner, s)) {
          knowledge.value = value;
          history.value = previous.items;
        }
      }
    } catch (value) {
      if (active(g, owner, s))
        error.value =
          value instanceof Error ? value.message : "正文暂时无法读取，请重试。";
    } finally {
      if (active(g, owner, s)) reading.value = false;
    }
  }
  async function refresh() {
    const g = generation,
      s = selection,
      owner = sessionId(),
      kind = selectedKind,
      id = selectedId;
    await load();
    if (active(g, owner, s) && kind && id) await open(kind, id);
  }
  function stop() {
    generation++;
    selection++;
    loadRequest++;
    selectedKind = null;
    selectedId = "";
    entries.value = [];
    source.value = null;
    knowledge.value = null;
    history.value = [];
    loading.value = false;
    reading.value = false;
    error.value = "";
    knowledgeError.value = "";
  }
  return {
    entries,
    source,
    knowledge,
    history,
    loading,
    reading,
    error,
    knowledgeError,
    load,
    refresh,
    open,
    stop,
  };
}
