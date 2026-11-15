# Personal OS first implementation slice

## Scope and execution boundary

This branch extends the reviewed Decision desktop code at `06319b3b7afcfae696a6bb61b7c5252742dfe677`.
Changes are implemented in an isolated source copy and tested in a disposable PostgreSQL database.
No existing Mac application, Ubuntu service, TGA/OpenClaw process or real database is changed.
V11 is a **new migration file**, not authorization to apply it to a current installation.

Origin is an independently stored module inside the existing Java backend for this slice. It reuses
account authentication, encryption, document validation/parsing and model transport. It does not
call `Domain.conversation`, `Domain.chat`, `DecisionCapture`, business attachments or business jobs.
This is not a completed standalone Personal OS deployment or a UI implementation.

Progress source is not available in this execution environment. No Progress API is guessed,
no report endpoint is used as a proposal preview, and no Progress source snapshot/change is claimed.
The Hindsight choice is unchanged; Memory Hub is not implemented by this slice.

## Storage and version semantics

- `origin_records` holds sessions, messages, generation state, immutable proposals and execution authorizations.
- `origin_attachments` holds Origin-owned attachments, separate from Decision business attachments.
- `origin_generation_events` persists chunk/status events for reconnect and incremental polling.
- `origin_requests` stores account/path/key/payload-bound encrypted idempotency receipts in the same transaction as the operation.
- `records.pos_revision` is a monotonic revision incremented by a database trigger on updates, including changes away and back.
- A decision context contains the problem revision and the complete active source ID/revision set for that problem.
  Source addition, deletion or modification invalidates an old confirmation even if a quoted substring is unchanged.
- Snapshot checking and Decision writes share the existing `Store.write` transaction/lock. Legacy APIs remain compatible;
  `/decisions` accepts an optional `expectedContext`. Origin never calls that API with an unconfirmed model suggestion.

## HTTP contract (all routes under `/api/v1`)

All Origin routes require the existing account Bearer token. Device tokens cannot access them.
All POST routes require `Idempotency-Key`. Same key and different payload returns 409.

| Method/path | Body / behavior |
|---|---|
| POST `/origin/sessions` | `{title?}`; creates only an Origin session |
| GET `/origin/sessions` | Lists the account's Origin sessions |
| GET `/origin/sessions/{id}` | Session, persisted messages, attachment metadata and redacted generation state |
| POST `/origin/sessions/{id}/messages` | `{content?,attachments?,thinking?,provider?,model?}`; returns sessionId/messageId/generationId; no business writes |
| GET `/origin/generations/{id}` | queued/running/succeeded/failed/stopped, partial text and error; never exposes modelSnapshot |
| GET `/origin/generations/{id}/events?after=N` | Persisted delta/status events; nextAfter cursor for incremental output and reconnection |
| POST `/origin/generations/{id}/stop` | `{}`; prevents a later completion from publishing an assistant message |
| POST `/origin/generations/{id}/retry` | `{}`; explicit retry of the latest failed/stopped user turn; preserves one user message and original attachments |
| GET `/origin/attachments/{id}` | Authenticated binary download; missing or foreign IDs return 404 |
| GET `/problems/{id}/confirmation-context` | Transactional problem/source version snapshot |
| POST `/origin/proposals` | `{sessionId,action,parameters,expectedContext?}`; validates without business writes |
| GET `/origin/proposals/{id}` | Frozen parameters/context/digest/fixed requestId/state/result receipt |
| POST `/origin/proposals/{id}/commit` | `{digest,requestId}`; header key must equal the proposal's fixed requestId |
| POST `/origin/proposals/{id}/cancel` | `{digest}`; mutually exclusive with commit; no business writes |
| GET `/origin/decisions/{id}/execution-context` | Decision revision and related problem/source context |
| GET `/origin/executions/{id}` | Authorization and actual receipt state |
| POST `/origin/executions/{id}/claim` | `{executorId,digest,executionKey}` plus separate executor credential |
| POST `/origin/executions/{id}/result` | `{executorId,attemptId,status,receipt,actualResult}` plus executor credential |

Actions:
1. `decision.create_problem`: parameters are `{title,goal,constraints?,unknowns?}`. Uses existing domain validation and creation.
2. `decision.confirm_decision`: `{problemId,choice,rationale,expectedOutcome,evidence?,reviewAt?}` and required expectedContext.
   `confirmedAt` is written only after this separate approval.
3. `execution.authorize`: `{decisionId,executorId,action,target,scope:{...},arguments:{...}}` and required execution context.
   This creates an authorization, **not an executed result**.

The UI must display the immutable full parameters, target and context before sending commit.
It must retain requestId after a timeout and reconcile the proposal/receipt rather than invent a new key.
A 409 stale context means refresh and generate a new proposal for a new explicit approval.
Legacy `/conversations` and automatic DecisionCapture remain available to the old application but are not Origin routes.

## Execution safety

`ORIGIN_EXECUTOR_KEY` is a deployment-owned separate credential. It is empty by default, disabling
claim/result endpoints; never put it in the web UI or model input. Existing owner credentials alone
cannot report execution success. The executor identity, exact action/target/scope/arguments,
version snapshot and attempt are bound. A result needs an actual result object and receipt.
The backend records what an authenticated executor reports; it does not prove arbitrary external
side effects. No real TGA/Codex/Progress executor is connected in this slice. Tests use explicit fixtures.
Unknown outcomes are not automatically re-executed. External exactly-once execution, reconciliation
and revocation after a claim still require a concrete executor contract.

## Generation behavior

`ORIGIN_WORKER_ENABLED=false` by default so adopting source cannot silently start a new model queue.
In a separately approved test/deployment environment set it to true to enable the independent worker.
The account's existing model configuration is reused. No hardcoded real key or synthetic success fallback exists.

Model transport uses existing bounded SSE handling. Input includes at most 40 saved messages;
attachments use the existing validated image/document formats and isolated parser. Partial text and events
persist. Failed or stopped generations require an explicit retry; they do not duplicate user messages.
A worker lease expires after ten minutes; an interrupted running generation becomes an explainable failure,
retaining its partial text. It is not silently retried with possibly duplicated model calls.
The ordinary conversation handler exposes no business-writing model tools.

## Verification limits to preserve

Tests use disposable PostgreSQL, synthetic accounts/content and a local HTTP model fixture.
They can verify transitions, transactions, version conflicts, isolation, parser/transport integration and
persisted chunks. They do not prove real-model semantics, macOS UI behavior, true multi-process long-term
availability, a real external executor or production migration/rollback.
The lost-response case deliberately ignores a successful response then replays its key; it is not a real
network partition experiment. Lease-expiry tests explicitly set timestamps; they are not wall-clock
long-duration recovery evidence. Reopening is tested by fresh service instances and GETs against persisted
state, not a full host reboot.

The repository asks for an independent sub-agent review. No independent execution agent is available
in this environment; own code review and CI are not represented as that independent review.

## Next source access needed

Provide an accessible snapshot/repository of Progress's actual source (including its local project rules,
build/test files and the four audited implementation files), excluding credentials, runtime databases,
real uploads and business data. Only then can the Progress proposal/atomic attachment adapter be
implemented against actual Planner/revision/requestId/transaction semantics rather than guessed APIs.
