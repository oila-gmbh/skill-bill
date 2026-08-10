# SKILL-176 · Subtask 5 — `skill-bill goal repair`

## Scope

Give the operator a supported path to clear a wedged goal child, so recovering a run never requires hand-written SQL against `review-metrics.db`.

Primary sites:

- `runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt` — the existing subcommand family, including `GoalResetCommand` (line 560), `GoalReplanCommand` (line 636), and `GoalAcceptCommand` (line 675), whose argument shape, confirmation style, and output format the new command follows.
- `runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerWorkflowStores.kt` — the durable write seam the repair executes through.

Subtasks 1 through 4 make the runtime self-heal each wedge class going forward. This subtask covers the residue: children already wedged in a local database before the fixes land, and any future wedge whose cause the runtime cannot classify. Both defects on goal SKILL-15 were cleared by editing the row directly — no validation, no backup, no record of what changed.

The command is a repair tool, not a reset. `goal reset` discards; this must preserve completed subtask work, commit shas, and review pass history while clearing only the field that wedges the resume.

## Acceptance Criteria

1. `skill-bill goal repair <issue-key>` inspects the goal's children and reports each detected wedge class with the durable field and value responsible, taking no action by default.
2. The command clears each wedge class covered by this feature: a continuation artifact missing `validation_depth`, an unreachable stored review or remediation base, and a stale blocked `goal_continuation_outcome`.
3. A repair refuses to act when the row is not wedged, and says which check passed rather than reporting success.
4. A repair preserves completed subtask commit shas, review pass results, and audit repair state; only the field that wedges the resume changes.
5. Every repair writes durable evidence of the change — field, prior value, new value, and the wedge class it was diagnosed as — so a hand-repair is reconstructable afterward.
6. A repair is atomic: a failure mid-way leaves the row exactly as it was, with no partial edit.
7. The command declines to repair a child whose worker lease is live, since a running worker owns that state.
8. Repairing a goal that is already healthy is a no-op that exits zero and says so.
9. `skill-bill goal repair --help` documents each wedge class it can clear and what it does not touch.
10. A test wedges a durable row for each of the four defect shapes, runs the repair, and asserts both that the resume proceeds afterward and that completed work survived.

## Non-Goals

- Repairing arbitrary durable state, or exposing a general-purpose row editor.
- Replacing `goal reset`, `goal replan`, or `goal accept`.
- Automatic repair during a normal resume. Subtasks 1 through 4 own the automatic paths; this command is the explicit operator escape hatch.
- Snapshotting or backing up the database, which `prune-snapshots` and existing operator practice already cover.

## Dependencies

- Subtask 1, non-optional — the repair must apply the same absent-versus-set semantics the runtime now uses.
- Subtask 2, non-optional — reachability classification is what the repair diagnoses a base against.
- Subtask 4, non-optional — the staleness rule defines when a blocked outcome is clearable.

Subtask 3 is not a dependency: it prevents a wedge rather than defining one.

## Validation Strategy

- Seed each wedge shape from durable artifacts JSON matching the observed SKILL-15 rows, then assert resume succeeds after repair.
- Assert preservation explicitly: commit shas and pass results compared before and after, not merely a successful exit.
- A live-lease test asserting refusal.
- A healthy-goal test asserting the no-op path and exit code.
- CLI output assertions covering the inspect-only default, since acting by default would make this as dangerous as the SQL it replaces.

## Next Path

Feature complete. Verify against the parent spec acceptance criteria.
