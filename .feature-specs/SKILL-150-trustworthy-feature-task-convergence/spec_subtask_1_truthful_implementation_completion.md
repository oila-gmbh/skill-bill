# SKILL-150 Subtask 1: Truthful Implementation Completion

## Scope

Make implementation completion a runtime-verified claim. Separate incomplete-work
continuation from malformed or contract-invalid output correction, and preserve
the complete bounded prior implementation receipt for every retry and resume.

## Acceptance Criteria

1. An implementation phase reporting `completed` advances only when its receipt
   closes every task ID declared by the authoritative executable plan and has no
   unresolved item or actionable deviation.
2. A task may leave the implementation phase incomplete through `blocked` or
   `failed`, or move through an explicit governed replan or decomposition
   outcome; omission from `completed_task_ids` cannot be hidden by a top-level
   `completed` status.
3. Audit and review consumers never receive an implementation receipt that claims
   completion while plan tasks remain open.
4. Retryable `blocked` and `failed` envelopes remain schema-valid terminal or
   continuation outcomes and are not converted into `schemaInvalid` merely to
   enter the bounded correction policy.
5. Actual extraction, JSON Schema, projection, reconciliation-report, and
   output-verification failures continue to use the SKILL-153 structural-repair
   and schema-correction path with its existing bounded cap.
6. Incomplete-work retries use a distinct prompt that says to continue the
   implementation and includes the complete bounded prior receipt: completed
   task IDs, changed paths, deviations, unresolved items, reconciliation
   evidence, repository checkpoint, and failure disposition.
7. Retry and resume reconstruct the same continuation projection from durable
   records; they do not depend on the latest in-memory prompt or a replaceable
   phase-record snapshot.
8. The runtime cannot be induced to escape an incomplete-work loop by changing
   only the top-level status to `completed`; the completion gate reports the
   exact missing task or unresolved field.
9. Status and telemetry distinguish semantic implementation continuation, schema
   correction, process retry, crash resume, and audit or review re-entry.
10. Standalone and goal-child tests cover partial implementation, multi-segment
    completion, crash between receipt persistence and workflow advance,
    malformed output, explicit replan, and exact completion.

## Non-Goals

- Requiring one agent process to finish every implementation.
- Running the full final validation suite inside implementation.
- Treating a producer receipt as proof that repository behavior is correct.
- Removing bounded correction or operator-decision policies.

## Dependency Notes

No upstream subtask. Durable attempt history is owned here: persist the bounded
receipt/attempt records this gate and its continuation projection need, following
the runtime-contract recipe and append-only name-keyed migrations. The quarry
branch's `94a94fe4`/`ed746dbd` implementations (completion decision, continuation
prompt, `RetryableTerminal` attempt handling, receipt persistence) are the
starting material; re-validate them against current `main`'s `AttemptResult` and
structural-repair seams instead of re-deriving from scratch.

## Validation Strategy

- Reproduce the SKILL-134 sequence where two honest incomplete outputs were
  followed by a false completed receipt; assert the third receipt cannot advance.
- Test that incomplete retries receive their complete prior receipt and a
  continuation directive, while real schema errors receive the schema correction
  directive.
- Test exact planned-task coverage, empty and non-empty unresolved items,
  actionable deviations, replan, decomposition, and audit-reentry receipts.
- Kill the process before and after the transactional advance and verify one
  resumable attempt with no lost obligations.
- Run focused runtime-domain, runtime-application, runtime-contracts, and CLI
  status tests.

## Next Path

Continue with Subtask 2 to apply the same durable closure guarantees to audit
repair.
