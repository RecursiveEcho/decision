# Personal OS batch 1 implementation handoff

Status: partial implementation completed and verified in isolated CI; no deployment or real-data migration.
Execution date: 2026-11-15 UTC. Source access began approximately 03:01; implementation validation completed 03:31.

## Source and branch

- Reviewed baseline: `codex/decision-desktop`, `06319b3b7afcfae696a6bb61b7c5252742dfe677`.
- Task branch: `codex/personal-os-confirmation-batch1`.
- Verified implementation commit: `5dcceefdb1c2f33cc50c3d880bed69459a18bb7d`.
- This follow-up only records evidence and reduces CI to read-only repository permissions, with no self-modification.
- Existing default/integration/desktop branches and local applications were not switched or merged.

## Actual implementation

Five new Java classes: OriginStore, OriginChat, OriginWorker, OriginConfirmations and OriginApi.
Modified existing Store, Domain, Api, ApiFilter and application.yml; added V11 migration, OriginBoundaryTest,
backend/docs/PERSONAL_OS_BATCH1.md and the backend contract addendum.

Origin is independently stored within the existing Decision Java service in this slice. It reuses account
authentication, encryption, file validation/parser and model transport, but does not invoke legacy conversation
creation, automatic decision capture, business attachment insertion or business jobs for ordinary chat.
It is not yet the standalone Personal OS service or the finished desktop app.

Implemented fixed-payload proposals with account/session/action/target/context/digest/request-key binding,
transactional problem and full related-source revision checks, monotonic record revisions, cancel/commit
exclusion and idempotent receipts. Problem creation, final decision confirmation and execution authorization
are separate operations. External execution is not claimed successful on authorization: a separately
credentialed executor must return an attempt-bound actual receipt. No real executor is connected.

Generation state, message history, Origin-owned attachments and incremental events are persistent.
Stop, explicit retry, latest-turn checks and interrupted-lease recovery are implemented. The worker is disabled
by default. The generation handler does not expose business-writing model tools. Automated natural-language
proposal/tool orchestration and Memory Hub integration remain unfinished.

## Verified evidence

- First implementation run: `43672382216`, job `132685060436`: 172 tests; 7 failures, 0 errors, 0 skipped.
  Six failures exposed canonical JSON numeric-node equality across HTTP serialization; one exposed an invalid
  device fixture. No tests were removed or disabled. The first-run evidence is retained.
- Corrected run: `43672623738`, job `132685819626`: 172 tests, 0 failures, 0 errors, 0 skipped;
  `./mvnw -B -DskipTests package` also succeeded.
- New suite: OriginBoundaryTest, 19 tests. Existing suites: 153 tests.
- Corrected artifact ID: `14145136848`; ZIP SHA-256:
  `77fd704e1b59e9e780c838d1282575a97d16391e13fe38c7bceee49f27dd6e02`.
- All 14 implementation/contract files in the returned tested source archive match the isolated working copy
  byte for byte. Source archive recorded the verified implementation commit and a clean working tree.
- Tests use disposable PostgreSQL 16, Java 17, synthetic accounts/documents, and a local HTTP streaming model
  fixture. No existing databases, external model credentials or production endpoints are used.

Covered: ordinary chat/cancel no business writes; double-confirm race; cancel/commit race; same-key replay and
payload conflict; stale problem/source/addition/deletion/ABA checks; distinct consent stages; account/device
isolation; old synthetic records and attachments; PNG/text attachments; partial output, stopping and explicit
retry; missing-provider failure without fake success; unsupported Progress action rejection.

## Limits and next access requirement

- Progress source/workspace was not accessible. No snapshot, modifications, pure proposal endpoint,
  atomic business attachment submission or working Progress adapter are claimed.
- The prescribed Mac Personal OS directory was not available; no existing local project was modified.
- Need an accessible Progress source snapshot or repository, including build/test files and local project rules,
  excluding credentials, runtime databases and real uploaded/business data.
- The lost-response test ignores a successful reply before replay, not a real socket partition.
- Lease expiry uses manipulated timestamps; reopen uses fresh service instances and stored data, not a host reboot.
- No real model semantics, macOS UI, full external executor, real legacy-data migration/rollback, persistent
  multi-process recovery or independent sub-agent review is asserted.
- Source-image/document support reuses existing parsers, but the new Origin flow specifically exercised PNG and
  plain text; existing parser regression tests do not equal full Origin UI PDF/Word acceptance.
- V11 ran only in temporary CI databases. Do not launch this branch against a real V10 database without the
  separate approval and backup/migration validation required by the user.
- Do not merge, deploy, restart production services or migrate user data based on this handoff alone.
