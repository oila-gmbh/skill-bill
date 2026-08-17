# SKILL-194 Subtask 3 — Goal session accounting removal

## Scope

Delete `GoalSessionAccounting` and everything that carries it. The record exists only to report
provider token usage: its `available` / `unavailableReason` pair exists solely to say whether token
data was found (`GoalRunnerAccountingModels.kt:80-95`), and the session identity it also carries —
`childSessionPath`, `childSessionId` — is already recorded on `GoalAttemptLedgerEntry`
(`:181-182`). Stripping only the token fields would leave a record whose remaining purpose is already
served elsewhere, so it goes wholesale.

Delete from `runtime-domain/.../goalrunner/model/GoalRunnerAccountingModels.kt`:

- `GoalSessionAccounting` (`:16-62`), including `toArtifactMap`
- `GoalSessionAccountingParser` (`:72-114`)
- `GoalSessionAccountingFields` (`:116-126`)
- `GoalSessionAccountingHistory` (`:128-137`)
- `GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY` and `GOAL_SESSION_ACCOUNTING_LIMIT` (`:5-6`)

Delete the wiring:

- the `goal_session_accounting` artifact write in `GoalRunnerLedgerRecorder`
- its store handling in `GoalRunnerWorkflowStores`
- its retention entry in `GoalHistoryArtifactRetention`
- its projection in `WorkflowGoalObservabilityMcpMapping`
- its port surface in `GoalRunnerPortModels`
- its allowance in `RuntimeArchitectureTest`

`GoalAttemptLedger`, `GoalAttemptLedgerEntry`, `GoalAttemptLedgerAction`,
`GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY`, and `GOAL_ATTEMPT_LEDGER_LIMIT` are untouched. They carry no token
field and remain the record of child activation, resume, retry, and terminal outcome.

Goals whose durable state already contains a `goal_session_accounting` artifact must read back
normally through the subtask 1 seam, with the artifact dropped and a degradation record emitted. The
MCP goal observability projection must omit the section without failing, for both new and pre-existing
workflows.

## Acceptance Criteria

1. `GoalSessionAccounting`, `GoalSessionAccountingParser`, `GoalSessionAccountingFields`,
   `GoalSessionAccountingHistory`, `GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY`, and
   `GOAL_SESSION_ACCOUNTING_LIMIT` no longer exist.
2. No goal runner recorder, store, retention policy, port model, or MCP mapping references goal session
   accounting.
3. `GoalAttemptLedger`, `GoalAttemptLedgerEntry`, `GoalAttemptLedgerAction`, and the attempt ledger
   artifact key and limit are unchanged, and still record child activation, resume, retry, timeout,
   interruption, and final reconciled outcome.
4. A goal whose durable state already contains a `goal_session_accounting` artifact reads back
   successfully, with the artifact dropped by the subtask 1 seam and one degradation record emitted.
5. The MCP goal observability projection omits the goal session accounting section without failing, for
   both a newly created goal and a pre-existing one.
6. A goal run completes end to end with no goal session accounting written at any phase boundary.
7. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Touching the attempt ledger in any way.
- Preserving `childSessionPath`, `childSessionId`, `model`, or `finalStatus` under a new record; the
  attempt ledger already carries the session identity fields worth keeping.
- Migrating or deleting historical `goal_session_accounting` artifacts from durable state. They are
  tolerated on read and left in place.
- Touching review, transport, planning-sweep, or telemetry surfaces.

## Dependency Notes

Depends on subtask 1 for the artifact-read tolerance and its degradation record. Independent of
subtask 2 — the goal session accounting surface shares the broken convention but no code with review
accounting, so the two removals do not interact.

## Validation Strategy

- Read a stored goal workflow whose artifact map contains `goal_session_accounting`; assert the read
  succeeds, the artifact is absent from the result, and one degradation record is emitted.
- Assert the MCP goal observability projection for that same workflow returns successfully with the
  section omitted.
- Assert a goal run completes with no goal session accounting artifact written.
- Assert the attempt ledger still records its full action set across a run with a resume and a retry.

Delete `GoalRunnerAccountingModelsTest`'s goal session accounting coverage and the corresponding
assertions in `WorkflowServiceTest`; keep their attempt ledger coverage intact. Then
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
