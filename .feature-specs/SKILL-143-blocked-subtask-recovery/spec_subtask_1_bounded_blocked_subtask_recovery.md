# Subtask 1 - Implement bounded blocked-subtask recovery

## Scope

Implement one explicit, subtask-scoped goal reset path that removes the named
subtask's incompatible child workflow while preserving planning checkpoints and
all unrelated subtask state. Update soft-reset and goal-wide hard-reset
diagnostics, and make the attempt ledger recommend the recovery action that
matches the durable child state.

The implementation includes CLI parsing and output, application/domain behavior,
workflow-store persistence, projection reconciliation, and regression coverage at
the relevant seams.

## Acceptance Criteria

1. The goal reset CLI accepts an explicit subtask selector for child-workflow deletion and rejects missing, malformed, unknown, or incompatible selectors without mutation.
2. The scoped operation deletes only the selected subtask's incompatible child workflow and preserves that subtask's immutable planning checkpoints.
3. Every unselected subtask retains its status, runtime fields, commit, workflow relationship, and out-of-band acceptance record.
4. Soft-reset output detects a preserved child workflow that will immediately re-block, names its workflow ID, and prints the exact scoped recovery command.
5. Attempt-ledger output uses distinct safe actions for a genuinely resumable block and a stale terminal child that must be deleted.
6. Before goal-wide hard reset mutation, output lists every completed-subtask acceptance that will be discarded and provides a restorable `skill-bill goal accept` command with its commit and reason.
7. End-to-end recovery of one stale terminal child returns that subtask to runnable state without requiring unrelated completed subtasks to be accepted again.
8. Regression tests prove destructive operations remain explicit and no manifest projection is treated as authoritative workflow state.

## Non-Goals

- Automatic retry after recovery.
- Changing the rule that blocked subtasks require explicit operator action.
- Altering review stack routing or agent-install repair behavior.

## Dependency Notes

This is the only executable subtask and has no dependencies.

## Validation Strategy

- Exercise CLI validation and rendered diagnostics with focused command tests.
- Exercise workflow-store mutations and invariants with application and persistence tests.
- Cover resumable, blocked-with-live-child, and blocked-with-terminal-child ledger cases.
- Cover a goal containing an unrelated accepted completed subtask and assert its durable state survives scoped recovery.
- Run `bill-code-check` for the affected Kotlin/runtime scope and any repository contract checks it routes to.

## Next Path

Implement through `bill-feature-task` under the parent SKILL-143 goal, then commit
the cohesive runtime, CLI, persistence, and test changes as this subtask.

