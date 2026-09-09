# Goal runtime stale workflow snapshots

Issue: 332
Status: Pending
Source: GitHub issue 332

## Problem statement

The goal runtime reads every `bill-feature-task` workflow snapshot while it searches for continuation candidates. A durable database from before the workflow-state contract bump can contain snapshots that no longer validate. The first invalid row currently aborts execution and read-only status commands, including requests for unrelated goals.

The runtime needs to isolate invalid historical rows from valid work. Operators also need a supported way to inspect and retire those rows without editing SQLite by hand.

## Intended outcome

Valid goals continue to run and report status when unrelated historical workflow snapshots fail current schema validation. The runtime warns with enough identity to locate each skipped row, preserves loud failure for an unreadable snapshot belonging to the requested goal during execution, and exposes a safe stale-workflow retirement command.

## Acceptance Criteria

1. Continuation candidate loading validates each task-runtime snapshot independently and skips snapshots that fail current workflow-state schema validation when they do not belong to the requested issue key.
2. Every skipped snapshot emits a warning containing its workflow id and the validation failure, without logging the snapshot payload or hiding unrelated persistence failures.
3. A requested goal does not proceed as though its own unreadable workflow snapshot were resumable. Execution surfaces the existing typed workflow-state schema error when the invalid snapshot can be identified as belonging to that goal.
4. `goal status` and its refresh/watch projection remain usable when the database contains invalid snapshots for unrelated goals, and the projection reports valid persisted state rather than aborting on the first stale row.
5. A supported stale-workflow retirement command lists invalid task-runtime snapshots by default without mutating durable state, identifies the workflow id, issue key when available, and validation failure, and only deletes selected stale rows after an explicit confirmation option.
6. Retirement refuses to delete snapshots that validate under the current contract and reports a clear result when no stale rows exist.
7. Regression tests cover a mixed store containing valid and invalid snapshots, execution of an unrelated goal, status projection, warning emission, dry-run retirement, confirmed retirement, and protection of valid snapshots.
8. The implementation preserves the current workflow-state contract version and existing behavior for valid snapshots.

## Constraints

- Keep workflow-state schema validation at the existing persistence and runtime boundaries.
- Do not rewrite, upgrade, or silently discard stale rows during ordinary execution or status reads.
- Use the existing diagnostics and typed error seams. Do not log raw artifact JSON, transcripts, or command output.
- Keep the retirement operation scoped to invalid task-runtime workflow snapshots and make its mutating path explicit.
- Follow the repository's Kotlin architecture, package, file-size, and test-value rules.

## Affected areas

- Goal continuation candidate reconciliation and authoritative outcome loading.
- Goal status and refresh/watch projection paths.
- Goal CLI command registration and workflow-state persistence operations.
- Runtime application, SQLite infrastructure, and focused Kotlin tests.

## Non-goals

- Changing the workflow-state schema contract version.
- Automatic migration of pre-0.3 snapshots into the current shape.
- Removing valid workflows, resetting an entire goal, or changing checkpoint pruning behavior.
- Repairing arbitrary malformed database rows outside task-runtime workflow snapshots.
- Replacing the existing goal runner or status projection model.

## Validation approach

Run the focused runtime application, SQLite infrastructure, and CLI tests for candidate loading, status projection, and stale-workflow retirement. Run the repository quality gate selected by the dominant Kotlin pack after implementation. Verify the command help and dry-run output against a fixture containing both valid and stale rows.

## Next path

Implement the single executable subtask, then let the governed goal runtime run review, validation, and finalization.
