---
status: Complete
---

# SKILL-66 Subtask 4 - Goal Stats MCP and CLI Surface

Parent spec: [.feature-specs/SKILL-66-feature-goal-telemetry/spec.md](./spec.md)
Issue key: SKILL-66

## Scope

Expose goal telemetry aggregates through a `goal_stats` MCP tool and a
top-level `goal-stats` CLI command, mirroring the shape, conventions, and
empty-store behavior of `implement-stats` / `verify-stats` and their MCP
counterparts. Aggregation logic lives in the application layer over the
Subtask 2 queries; MCP and CLI are thin adapters.

This subtask owns the read surface only. Contracts (Subtask 1), storage
(Subtask 2), and emission (Subtask 3) are done; remote-stats mapping is
Subtask 5.

## Acceptance Criteria

1. An application-layer stats service method aggregates: total runs,
   completed/blocked counts and rates, duration aggregates (avg at minimum,
   matching whatever implement/verify report), per-subtask outcome breakdown
   (`complete`/`blocked`/`skipped` totals), top blocked subtasks with
   `blocked_reason`, and a most-recent-run summary.
2. The `goal_stats` MCP tool (registered in Subtask 1) is dispatched in
   `McpToolDispatcher.nativeHandlers` to that service method; its arguments
   (date-range/grouping per the Subtask 1 input schema) behave like
   `feature_implement_stats` / `feature_verify_stats`.
3. A top-level `goal-stats` CLI command exists beside `implement-stats` /
   `verify-stats` with the same option conventions (`--format` etc.), the
   same human-readable and JSON output discipline, and graceful, explicit
   empty-store output (no fabricated zero-rates without a "no runs recorded"
   signal, matching the existing commands' behavior).
4. Resumed runs are reported per the Subtask 3 semantics: segments group by
   issue key where the existing stats conventions group, and nothing is
   double-counted.
5. Existing stats tools and commands are unchanged.
6. Tests: MCP golden tests for `goal_stats` (populated and empty store), CLI
   tests for `goal-stats` in both formats, and application tests for the
   aggregation math including blocked-rate and per-subtask breakdown edge
   cases (all-blocked, all-skipped, single-run stores).

## Non-Goals

- No remote-stats mapping or proxy-capabilities change (Subtask 5).
- No new emission events or schema branches.
- No dashboards or visualization output.
- Do not move aggregation into the CLI or MCP layers.

## Dependency Notes

Depends on: Subtask 1 (the `goal_stats` tool contract), Subtask 2 (aggregate
queries), and Subtask 3 (emission semantics that define what the numbers
mean).

Provides the local read surface Subtask 5 connects to remote stats.

## Validation Strategy

MCP golden tests; CLI command tests; application aggregation tests;
`RuntimeArchitectureTest` continues to pass.

## Next Path

Run bill-feature-task on spec_subtask_5_remote-stats-integration-and-validation-gate.md.

## Spec Path

.feature-specs/SKILL-66-feature-goal-telemetry/spec_subtask_4_goal-stats-mcp-and-cli-surface.md
