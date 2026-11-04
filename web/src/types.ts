export interface Evidence {
  sourceId: string;
  quote: string;
  relation: string;
}
export interface Problem {
  id: string;
  title: string;
  goal: string;
  constraints: string[];
  unknowns: string[];
}
export interface Message {
  id: string;
  role: string;
  content: string;
  evidence: Evidence[];
  uncertainties: string[];
  mode: string;
  thinking?: Thinking;
  attachments?: Attachment[];
  memoryChanges?: {
    action: "ADD" | "UPDATE" | "DELETE";
    memoryId: string;
    content: string;
    scope: "global" | "problem";
    revision: number;
  }[];
  decisionRecord?: Decision;
}
export type Thinking = "off" | "low" | "high" | "max";
export interface Attachment {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  kind: "image" | "document";
  width?: number;
  height?: number;
  status: "pending" | "processing" | "ready" | "partial" | "failed";
  error: { code: string; message: string; retryable: boolean } | null;
  coverage?: {
    pages?: number;
    processedPages?: number;
    truncated: boolean;
    notice?: string;
  };
}
export interface Source {
  id: string;
  problemId: string;
  title: string;
  text: string;
  kind: string;
  originUrl: string | null;
  status: string;
  capturedAt: string;
}
export interface Memory {
  id: string;
  content: string;
  kind: string;
  status: string;
  revision: number;
  evidence: Evidence[];
  problemId?: string;
  scope?: "global" | "problem";
  origin?: "chat" | "manual";
  sourceMessageId?: string;
  sourceQuote?: string;
}
export interface Topic {
  id: string;
  problemId: string;
  title: string;
  markdown: string;
  syncStatus: string;
  wikiUrl: string | null;
  evidence: Evidence[];
}
export interface Discussion {
  id: string;
  title: string;
  reason: string;
  status: string;
  evidence: Evidence[];
}
export interface Review {
  id: string;
  outcome: string;
  reflection: string;
}
export interface Decision {
  id: string;
  choice: string;
  rationale: string;
  expectedOutcome: string;
  confirmedAt: string;
  reviews: Review[];
  evidence: Evidence[];
  origin?: "chat";
  userMessageId?: string;
  quote?: string;
  status?: "active" | "superseded";
  supersedesId?: string;
}
export interface Device {
  id: string;
  name: string;
  desiredState: string;
  reportedState: string;
  lastHeartbeatAt: string | null;
  lastError: string | null;
}
export interface Job {
  id: string;
  status: string;
  mode: string;
  error: { message: string } | null;
}
export interface Status {
  model: { configured: boolean; lastCallStatus: string };
  wiki: { configured: boolean; status: string };
  observer: { lastSeenAt: string | null };
  usage: {
    requests: number;
    inputTokens: number;
    outputTokens: number;
    costEstimate: number | null;
    currency: string | null;
  };
}
