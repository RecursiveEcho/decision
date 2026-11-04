export const OVERVIEW_STORAGE = "decision.memory-overview";
export interface OverviewOperation {
  key: string;
  retry: boolean;
  previousJobId: string | null;
  unconfirmed: boolean;
}
export function readOverviewOperations(
  owner: string,
): Map<string, OverviewOperation> {
  try {
    const saved = JSON.parse(
      sessionStorage.getItem(OVERVIEW_STORAGE) || "null",
    );
    if (saved?.owner !== owner || !Array.isArray(saved.operations))
      return new Map();
    return new Map(
      saved.operations.filter((row: unknown) => {
        if (!Array.isArray(row) || typeof row[0] !== "string") return false;
        const op = row[1];
        return (
          op &&
          typeof op.key === "string" &&
          typeof op.retry === "boolean" &&
          typeof op.unconfirmed === "boolean" &&
          (op.previousJobId === null || typeof op.previousJobId === "string")
        );
      }),
    );
  } catch {
    return new Map();
  }
}
export function saveOverviewOperations(
  owner: string,
  operations: Map<string, OverviewOperation>,
) {
  sessionStorage.setItem(
    OVERVIEW_STORAGE,
    JSON.stringify({ owner, operations: [...operations] }),
  );
}
