export interface Evidence { sourceId: string; quote: string; relation: string }
export interface Problem { id: string; title: string; goal: string; constraints: string[]; unknowns: string[] }
export interface Message { id: string; role: string; content: string; evidence: Evidence[]; uncertainties: string[]; mode: string }
export interface Source { id: string; title: string; text: string; kind: string; originUrl: string | null; status: string; capturedAt: string }
export interface Memory { id: string; content: string; kind: string; status: string; revision: number; evidence: Evidence[] }
export interface Topic { id: string; title: string; markdown: string; syncStatus: string; wikiUrl: string | null; evidence: Evidence[] }
export interface Discussion { id: string; title: string; reason: string; status: string; evidence: Evidence[] }
export interface Review { id: string; outcome: string; reflection: string }
export interface Decision { id: string; choice: string; rationale: string; expectedOutcome: string; confirmedAt: string; reviews: Review[]; evidence: Evidence[] }
export interface Device { id: string; name: string; desiredState: string; reportedState: string; lastHeartbeatAt: string | null; lastError: string | null }
export interface Job { id: string; status: string; mode: string; error: {message: string} | null }
export interface Status { model: {configured: boolean; lastCallStatus: string}; wiki: {configured: boolean; status: string}; observer: {lastSeenAt: string | null}; usage: {requests: number; inputTokens: number; outputTokens: number; costEstimate: number | null; currency: string | null} }
