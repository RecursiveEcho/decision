import { expect, it } from "vitest";
import {
  domainColor,
  graphElements,
  referenceKey,
  safeGraphUrl,
} from "../src/knowledgeGraph";
import type { KnowledgeEntry } from "../src/types";
const entry = (id: string, relatedEntryIds: string[] = []): KnowledgeEntry =>
  ({
    id,
    title: "精炼知识 " + id,
    domains: ["开发"],
    relatedEntryIds,
  }) as KnowledgeEntry;
it("uses only explicit existing relationships, deduplicates reciprocal edges and keeps isolated knowledge", () => {
  const values = graphElements([
    entry("a", ["a", "b", "b", "missing"]),
    entry("b", ["a"]),
    entry("c"),
  ]);
  expect(values.filter((item) => item.data.source)).toHaveLength(1);
  expect(
    values.filter((item) => !item.data.source).map((item) => item.data.id),
  ).toEqual(["a", "b", "c"]);
  expect(values.find((item) => item.data.source)?.data).toMatchObject({
    source: "a",
    target: "b",
  });
});
it("does not infer an edge from shared domains or retain edges outside a filtered collection", () => {
  expect(graphElements([entry("a", ["b"]), entry("c")])).toHaveLength(2);
  expect(graphElements([])).toEqual([]);
});
it("keeps full original titles out of truncated canvas labels without breaking unicode and preserves deterministic colors", () => {
  const value = { ...entry("a"), title: "🧠".repeat(30) };
  const elements = graphElements([value]);
  expect(Array.from(elements[0]!.data.label)).toHaveLength(23);
  expect(value.title).toBe("🧠".repeat(30));
  expect(domainColor("开发")).toBe(domainColor("开发"));
});
it("accepts only absolute http(s) links without embedded credentials", () => {
  for (const url of [
    "javascript:alert(1)",
    "data:text/html,test",
    "file:///tmp/a",
    "/local",
    "https://user:pass@example.org",
  ])
    expect(safeGraphUrl(url)).toBeUndefined();
  expect(safeGraphUrl("https://example.org/note?q=1")).toBe(
    "https://example.org/note?q=1",
  );
});
it("resolves source and message references independently even when identifiers coincide", () => {
  expect(referenceKey({ kind: "source", id: "same" })).not.toBe(
    referenceKey({ kind: "message", id: "same" }),
  );
});

it("suppresses only an identical opening heading for display and keeps the stored knowledge unchanged", async () => {
  const { graphBody } = await import("../src/knowledgeGraph");
  const markdown = "# 已确认结论\n\n保留关键条件。\n\n## 例外\n仍然保留。";
  expect(graphBody("已确认结论", markdown)).toBe(
    "保留关键条件。\n\n## 例外\n仍然保留。",
  );
  expect(markdown.startsWith("# 已确认结论")).toBe(true);
  expect(graphBody("其他标题", markdown)).toBe(markdown);
  expect(graphBody("已确认结论", "原文中的 # 已确认结论")).toBe(
    "原文中的 # 已确认结论",
  );
});
