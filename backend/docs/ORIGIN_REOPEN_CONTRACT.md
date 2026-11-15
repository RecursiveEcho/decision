# Origin reopen and attachment integrity contract

This additive contract extends `PERSONAL_OS_BATCH1.md`. It is implemented on the isolated
`codex/personal-os-confirmation-batch1` branch. It does not authorize a deployment, branch merge,
service restart or migration of an existing installation. No new migration is introduced here.

## Reopen a conversation

`GET /api/v1/origin/sessions/{id}` retains `session`, `messages` and redacted `generations`,
and additionally returns:

- `proposals`: this account/session's immutable proposal records, including fixed parameters,
  expected business context, digest, stable requestId, current state and committed result receipt.
- `executions`: this account/session's separate execution authorizations and actual result receipts.

Both arrays are returned in persisted creation order. Loading them does not create a Decision problem,
confirm a decision, authorize an execution, or run any business job. A pending proposal may already be
stale relative to the business application: the commit transaction remains the authority for version
checks. Reloading does not generate a new proposal, request key, digest or business revision.

Clients should reconstruct their cards from these records. `authorized` is not `succeeded`.
A receipt for problem creation, a receipt for final decision confirmation, and an execution authorization
must remain distinct in the UI. Reconciliation after a timeout uses the original proposal ID and
requestId; never generate a fresh key merely because a response was not received.

## Unavailable attachment references

Origin message records keep their original attachment references. If a referenced attachment row
is unavailable within the same account/session/message, history retains its known metadata and returns:

```json
{
  "status": "unavailable",
  "error": {
    "code": "ATTACHMENT_REFERENCE_UNAVAILABLE",
    "message": "附件引用已失效，原始内容当前不可读取",
    "retryable": false
  }
}
```

This warning is computed for the response; history retrieval does not rewrite the message or erase
its reference. Direct download still returns 404 for unavailable or unauthorized IDs.
Before model invocation, the stored reference IDs/order must match the available attachment rows
for each included message. A mismatch fails that generation with the same error code, preserving
the original messages and preventing silent omission of material. The user may recover the original
attachment or provide material in a new conversation; no automatic retry loop or fabricated answer.

Encrypted or unparseable documents retain the existing parser error semantics and remain downloadable
within the original account. They are not treated as successful reads. Supported file handling and
resource bounds otherwise remain unchanged.

## Verification scope

`OriginContinuationTest` adds coverage for pending/cancelled cards, separate consent receipts,
reopened stale proposals, missing references, real PDF/Word parser transport, encrypted-document
failure, account isolation, and an HTTP client timeout after commit followed by stable-key replay.

The timeout fixture uses a loopback HTTP bridge which invokes Spring MockMvc and the real temporary
PostgreSQL transaction, then withholds the response until the HTTP client times out. This is stronger
than merely ignoring a returned value, but is not a full production proxy/Tomcat/network-partition test.
PDF and DOCX fixtures are synthetic; provider replies are explicitly synthetic streaming HTTP fixtures.
They test extraction and transport, not real-model document understanding or macOS UI behavior.

The first test-only CI run intentionally preceded the production fix and exposed the missing history
and attachment behavior. It also exposed a test-fixture registration request missing the pre-existing
Idempotency-Key requirement; the fixture was corrected rather than weakening the application contract.
No tests are removed, disabled or skipped to obtain a passing result. Exact run IDs and results belong
in the accompanying execution report.

## Remaining batch boundaries

- Progress source is still unavailable in this environment. No Progress implementation or source
  snapshot has been fabricated from audit descriptions.
- Natural-language proposal/tool orchestration, Memory Hub, real model/executor behavior and the
  final Personal OS desktop UI remain outside this continuation.
- Origin remains an independently stored module inside the existing Decision Java process in this
  slice, not a completed standalone deployment in the user's Mac Personal OS directory.
- No independent sub-agent review is claimed; no such execution agent is available here.
- V11 from the preceding batch is not newly applied to any existing database; only disposable CI
  databases are used for testing.
