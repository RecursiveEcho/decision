import type { ElementDefinition } from "cytoscape";
import type { KnowledgeEntry, KnowledgeRef } from "./types";

export const graphColors = [
  "#7c9ee6",
  "#bb91d5",
  "#67b5a4",
  "#d6a15e",
  "#d4819c",
  "#8db86d",
  "#85aab8",
];
export interface GraphReference extends KnowledgeRef {
  title: string;
  problemId?: string;
  originUrl?: string | null;
}
export function domainColor(domain: string) {
  let hash = 0;
  for (const character of domain)
    hash = (hash * 31 + character.codePointAt(0)!) >>> 0;
  return graphColors[hash % graphColors.length]!;
}
export function graphElements(entries: KnowledgeEntry[]): ElementDefinition[] {
  const ids = new Set(entries.map((entry) => entry.id));
  const edges = new Map<string, { source: string; target: string }>();
  for (const entry of entries) {
    for (const related of entry.relatedEntryIds) {
      if (related === entry.id || !ids.has(related)) continue;
      const [source, target] = [entry.id, related].sort() as [string, string];
      edges.set(JSON.stringify([source, target]), { source, target });
    }
  }
  const degrees = new Map<string, number>();
  for (const edge of edges.values()) {
    for (const id of [edge.source, edge.target])
      degrees.set(id, (degrees.get(id) || 0) + 1);
  }
  return [
    ...entries.map((entry, index) => ({
      data: {
        id: entry.id,
        label:
          Array.from(entry.title).length > 22
            ? Array.from(entry.title).slice(0, 22).join("") + "…"
            : entry.title,
        color: domainColor(entry.domains[0] || "待归类"),
        size: 12 + Math.min(degrees.get(entry.id) || 0, 8) * 1.5,
      },
      position: {
        x: Math.cos((index * 2 * Math.PI) / entries.length) * 180,
        y: Math.sin((index * 2 * Math.PI) / entries.length) * 180,
      },
    })),
    ...[...edges].map(([key, edge]) => ({
      data: { id: "edge:" + key, ...edge },
    })),
  ];
}
export function safeGraphUrl(value?: string | null) {
  try {
    const url = new URL(value || "");
    return ["https:", "http:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
export function referenceKey(reference: Pick<KnowledgeRef, "kind" | "id">) {
  return reference.kind + ":" + reference.id;
}

// Avoid repeating an identical opening heading in the reading panel. Stored Markdown stays intact.
export function graphBody(title: string, markdown: string) {
  const firstHeading =
    /^\s{0,3}#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*(?:\r?\n|$)/.exec(markdown);
  if (!firstHeading || firstHeading[1]?.trim() !== title.trim())
    return markdown;
  return markdown.slice(firstHeading[0].length).replace(/^\s*\n/, "");
}
