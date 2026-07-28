# SKILL-150 Subtask 1: Durable Convergence State

## Scope

Introduce the versioned contract, domain model, ports, SQLite persistence, and transactional services needed to preserve implementation attempts, audit gaps and repairs, and review findings and dispositions across retries, resumes, and repository generations.

The durable model may extend existing tables where their ownership and identity are correct. Add normalized tables where replaceable workflow artifact snapshots or the current unresolved-findings table cannot preserve append-only history and cross-generation relationships.

## Acceptance Criteria

1. A versioned runtime contract defines stable identities and generation relationships for implementation attempt outcomes, audit gaps and repair items, review findings and dispositions, repository checkpoints, and their workflow, phase, attempt, or review-pass provenance.
2. Normalized SQLite persistence stores append-only convergence events or immutable generation records with deterministic primary keys, foreign keys, uniqueness rules, indexes, timestamps, and bounded payload fields.
3. The authoritative records distinguish current state from history without deleting or overwriting the evidence required to explain a prior incomplete attempt, recurring audit gap, or unresolved review finding.
4. Recording a phase outcome and advancing its authoritative workflow state is atomic wherever either side without the other could skip required work after a crash.
5. Retry, resume, and crash reconciliation are idempotent: replaying one logical event neither duplicates it nor replaces conflicting evidence silently.
6. The model supports durable queries for unresolved implementation obligations, unresolved audit repair items, and unresolved review Blockers across all active generations.
7. Legacy artifact-only convergence state is imported or reconciled exactly once when compatible; incompatible records fail through typed contract errors or the established quarantine-and-regenerate path.
8. New migrations are append-only, preserve existing workflow and telemetry rows, and have deterministic upgrade and fresh-database parity coverage.
9. Raw prompts, raw phase output, private rejected-output bodies, complete diffs, and unbounded finding text are excluded; durable records retain bounded identifiers, classifications, summaries, digests, paths, and evidence references only.
10. Application code depends on injected ports and transaction boundaries rather than SQLite-specific conditionals.

## Non-Goals

- Changing implementation, audit, or review advancement policy in this subtask.
- Migrating private rejected-output diagnostic bodies into the convergence store.
- Replacing workflow state, telemetry outbox, or goal progress tables wholesale.
- Adding platform-specific routing.

## Dependency Notes

No dependency. Subsequent subtasks use this durable authority instead of inventing per-phase persistence.

## Validation Strategy

- Validate the contract schema, Kotlin version constant, strict unknown-field rejection, and typed error mapping.
- Test fresh creation and every supported upgrade path.
- Test atomic write rollback, idempotent replay, conflicting duplicate rejection, and crash recovery.
- Test current-state and historical queries across multiple implementation, audit, and review generations.
- Assert sentinel raw content never enters the new records or their rendered diagnostics.
- Run the runtime contracts, application, ports, and SQLite module tests.

## Next Path

Continue with Subtask 2 to make implementation completion and retry behavior consume the durable model.

