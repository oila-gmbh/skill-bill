# Issue 340 · Subtask 2 — Repair apply SQLITE_BUSY lifecycle fix

## Scope

Fix `goal repair --apply` so it can persist wedge repairs without
`SQLITE_BUSY` when no external process holds `review-metrics.db`. The failure
mode is specific to the apply path: inspect reads succeed, `goal replan` writes
succeed at the same time, but repair apply fails—consistent with a read
connection held open across the write in the same command.

Trace `GoalRunnerRepairCoordinator.repair` through
`GoalRunnerChildRepairStore.diagnoseChildWedges` and
`applyChildWedgeRepairs`, ensuring read scopes close before write transactions
open. Add regression coverage for the `completed_upstream_missing_output` wedge
class that previously failed apply.

When `repair` or `operator-decision` refuse review remediation, surface a
pointer to scoped recovery documented on `goal reset --subtask` and
`--delete-child-workflow` so operators are not left without an escalation path.

## Acceptance Criteria

1. `goal repair --apply` completes wedge repair writes after printing inspect
   diagnoses when no live worker lease or external lock holds the database.
2. The `completed_upstream_missing_output` wedge repair path is covered by a
   regression test that would fail if a read connection blocks the apply write.
3. `goal replan` and other unrelated write paths remain unchanged in behavior.
4. Refusal messages from `repair` and `operator-decision` for review wedges
   mention scoped `goal reset --subtask` recovery where `goal reset --help`
   documents it.
5. Focused `GoalRunnerRepairTest` and CLI repair apply tests pass.
6. The dominant-stack quality check reports no new findings on touched modules.

## Non-Goals

- Changing which wedge classes repair can clear.
- Replacing SQLite or altering global WAL configuration.
- Implementing new operator-decision review remediation paths removed by design.

## Dependency Notes

- Depends on subtask 1 completing so the goal branch carries the citation fix
  before repair-path work lands.

## Validation Strategy

1. Run `GoalRunnerRepairTest` focused on apply paths, including
   `completed_upstream_missing_output`.
2. Run `CliGoalRepairRuntimeTest` apply coverage.
3. Run the dominant-stack quality check for changed Kotlin modules.

## Next Path

After this subtask commits, the decomposed goal is complete when all parent
acceptance criteria are satisfied.
