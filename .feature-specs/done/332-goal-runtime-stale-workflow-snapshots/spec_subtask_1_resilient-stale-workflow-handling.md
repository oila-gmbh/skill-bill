# Resilient stale workflow handling

Issue: 332
Subtask: 1
Status: Pending

## Scope

Update goal continuation candidate loading so one invalid historical `bill-feature-task` snapshot does not abort unrelated goal execution or outcome reconciliation. Keep the requested-goal failure boundary explicit. Make status and refresh/watch projections tolerate unrelated invalid rows. Add a supported `goal prune-stale-workflows` operator path with dry-run behavior by default and explicit deletion for selected invalid snapshots. Add focused regression coverage across the application, SQLite, and CLI seams.

## Acceptance Criteria

1. The candidate loader validates snapshots one at a time, retains valid candidates, skips unrelated snapshots that fail current schema validation, and emits a warning with the skipped workflow id and validation failure.
2. An invalid snapshot belonging to the requested issue is not treated as a missing or resumable candidate during execution; the execution path surfaces the typed schema error required by the existing contract.
3. `authoritativeOutcomes`, `goal status`, and status refresh/watch projection continue to return valid state when unrelated invalid task-runtime snapshots are present.
4. `goal prune-stale-workflows` is discoverable in CLI help, defaults to a report-only operation, identifies invalid task-runtime rows without exposing payload bodies, and requires an explicit confirmation option before deleting only the selected invalid rows.
5. The command leaves valid snapshots untouched and reports an empty result without mutation when no stale rows match.
6. Tests reproduce the original mixed-store failure, assert warning identity and typed failure behavior, verify status remains readable, and cover both dry-run and confirmed retirement.
7. The focused implementation tests and the dominant Kotlin quality gate pass without changing the workflow-state contract version.

## Non-goals

- Migrating stale snapshots to the current schema.
- Deleting valid workflow rows or broadening retirement to other workflow families.
- Changing normal continuation, outcome precedence, checkpoint, or goal reset semantics for valid state.
- Adding a second persistence model for workflow snapshots.

## Dependency notes

This subtask has no prerequisite subtasks. It owns the candidate-loading and status behavior needed before the retirement command can safely inspect the same rows.

## Validation strategy

Use focused unit and integration tests around `WorkflowGoalRunnerOutcomeReconcile`, the goal status projection assembler/service, the SQLite workflow-state adapter, and goal CLI parsing/formatting. Seed one current-contract row and one pre-0.3 or otherwise schema-invalid row. Assert that the valid row remains visible, the stale row produces an identity-bearing warning, dry-run performs no write, confirmed retirement removes only the stale row, and an invalid requested-goal snapshot follows the typed error boundary. Run the Kotlin pack quality gate after the focused tests.

## Next path

After this subtask reaches terminal success, the goal runtime can finalize the branch and publish its commit. If validation finds a defect, repair the same subtask within its bounded fix loop.
