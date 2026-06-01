---
status: Pending
---

# SKILL-61 Subtask 3 - CLI Watch, Status, and Diff UX

Parent spec: [.feature-specs/SKILL-61-goal-observability/spec.md](./spec.md)
Issue key: SKILL-61

## Scope

Expose observability through operator-facing CLI output for foreground goal
runs and read-only status/watch views. Add opt-in diff stat and selected-hunk
visibility sourced from local git/filesystem state rather than model context.

The default UX must stay useful and quiet. Detailed diff output is explicit.

## Acceptance Criteria

1. Foreground goal output renders structured observability lines by default at
   bounded cadence.
2. `goal status` includes latest observability state for active or blocked
   subtasks when present.
3. A watch mode can refresh status/progress without starting a new child
   implementation run.
4. Operators can request diff stat output for the active worktree state.
5. Operators can request selected diff hunks explicitly, with bounded output.
6. Raw child stdout/stderr remains hidden by default and still appears under
   the existing debug-child-output behavior.
7. Output remains line-oriented and machine-parseable enough for future desktop
   or MCP surfaces.
8. CLI help documents the observability flags and their cost/noise tradeoffs.

## Non-Goals

- Do not build a desktop UI in this subtask.
- Do not stream complete diffs continuously by default.
- Do not require provider-specific UI features such as `/subagent`.

## Dependency Notes

Depends on: 2
Requires runtime events and reconciliation behavior before CLI rendering can be
made authoritative.

## Validation Strategy

Add CLI tests for default output, watch/status rendering, diff stat, bounded
hunk output, and debug-child-output interaction.

## Next Path

Run bill-feature-task on spec_subtask_4_validation-docs-operator-scenarios.md.

## Spec Path

.feature-specs/SKILL-61-goal-observability/spec_subtask_3_cli-watch-status-diff-ux.md
