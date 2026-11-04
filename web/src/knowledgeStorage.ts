import type { Job } from "./types";

export const KNOWLEDGE_STORAGE = "decision.knowledge-operations";
export interface KnowledgeOperation {
  kind: "prepare" | "accept" | "dismiss";
  key: string;
  proposalId?: string;
  expectedTargetRevision?: number | null;
  job?: Job;
}
export function readKnowledgeOperations(owner: string) {
  const result = new Map<string, KnowledgeOperation>();
  try {
    const data = JSON.parse(
      sessionStorage.getItem(KNOWLEDGE_STORAGE) || "null",
    );
    if (data?.owner !== owner || !Array.isArray(data.operations)) return result;
    for (const row of data.operations) {
      if (!Array.isArray(row) || typeof row[0] !== "string") continue;
      const op = row[1];
      if (
        !op ||
        !["prepare", "accept", "dismiss"].includes(op.kind) ||
        typeof op.key !== "string" ||
        (op.kind !== "prepare" && typeof op.proposalId !== "string") ||
        (op.kind === "accept" &&
          op.expectedTargetRevision !== null &&
          !Number.isInteger(op.expectedTargetRevision)) ||
        (op.job && typeof op.job.id !== "string")
      )
        continue;
      result.set(row[0], op);
    }
  } catch {
    /* Invalid local metadata cannot trigger a write. */
  }
  return result;
}
export function saveKnowledgeOperations(
  owner: string,
  operations: Map<string, KnowledgeOperation>,
) {
  sessionStorage.setItem(
    KNOWLEDGE_STORAGE,
    JSON.stringify({ owner, operations: [...operations] }),
  );
}
