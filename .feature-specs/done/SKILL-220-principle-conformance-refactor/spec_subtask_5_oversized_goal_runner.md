# SKILL-220 Subtask 5: Oversized Goal-Runner Decomposition

## Intended Outcome

Resolve the goal-runner share of P-08. `GoalRunnerWorkflowStores` (~3216),
`GoalRunner` (~2684), and neighbouring types each hold several
responsibilities. Split them into cohesive collaborators without multiplying
public abstractions.

## Scope

Decompose production files in the goal-runner cluster that exceed 500 lines,
including at least:

- `GoalRunnerWorkflowStores.kt` (~3216)
- `GoalRunner.kt` (~2684)
- `GoalPlanningSweep.kt` (~1555)
- `GoalPlanningPreparationStore.kt` (~1295)
- `GoalRunnerStatusService.kt` (~1076)
- `GoalSubtaskReviewState.kt` (~736)
- `GoalRunnerModels.kt` (~541)
- `GoalSubtaskReviewSummaryReducer.kt` (~533)
- `GoalRunnerChildRepairOperations.kt` (~503)

For each file, identify the distinct responsibilities (manifest persistence,
control/lease coordination, parent projection, outcome reconcile, launch
reconciliation, progress probing, planning sweep, status projection) and
extract each into a collaborator whose name states what it owns.

Keep extracted collaborators as narrow in visibility as their callers allow.
Preserve every atomic write that was atomic before. Do not open a
transaction in one collaborator and commit it in another. Do not hold a
lease across an external process wait.

`GoalRunner` remains the application entry for goal orchestration; extracting
launch or probe helpers must not create a second public runner.

Order each resulting file top-down. Remove detekt suppressions the split
makes unnecessary.

## Applicable Principles

- Order a file top-down; delete pass-through wrappers.
- Never hold a transaction, lease, or lock open across a wait on an
  external process.
- JDBC and SQLDelight types stay inside `runtime-infra-sqlite`.
- Prefer clear names and small functions over comments.

## Acceptance Criteria

1. Every file listed above is at or under 500 lines, and no new production
   file this subtask introduces exceeds 500 lines.
2. No extraction introduces a public type whose only caller is the file it
   was extracted from.
3. Every atomic write that was atomic before is atomic after, proved by the
   existing persistence, fencing, and goal-runner tests passing without
   assertion changes.
4. No transaction is opened and committed in different collaborators, and no
   lease or lock is held across an external process wait.
5. No SQL or JDBC type escapes `runtime-infra-sqlite`, confirmed by existing
   type-leak architecture tests.
6. Detekt complexity suppressions removed by the split are deleted rather
   than moved. Do not add new suppressions. Leftover `@Suppress` is
   SKILL-221.
7. `../../../scripts/validate` passes.
8. No test is added. If a split needs a new test to be safe, the split is
   wrong.

## Failure And Recovery Behavior

Unchanged. Every failure path, rollback, and recovery route must produce
identical typed results before and after.

## Non-Goals

- Feature-task runtime files (subtask 4) and remaining oversized files
  (subtask 6).
- Changing persistence schema, durable encodings, or goal CLI verbs.
- Introducing new ports or modules.

## Dependency Notes

Runs after subtasks 1, 2, and 3 so control-store typed decode and exhaustive
dispatch land before the stores are split. Independent of subtasks 4 and 6.
Must not run concurrently with subtask 1.

## Validation Strategy

`../../../scripts/validate`. Compare fencing, resume, and status-projection tests
before and after. Line counts of the listed files and of files this subtask
creates must all be ≤ 500.

## Next Path

Subtask 6 decomposes the remaining oversized units.
