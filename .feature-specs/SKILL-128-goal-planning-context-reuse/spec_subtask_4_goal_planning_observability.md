# SKILL-128 Subtask 4 - Goal planning observability and guidance

## Scope

Expose bounded preparation progress and correct telemetry attribution for the
new goal planning stage, then update governed goal/runtime guidance and
architecture documentation. This subtask owns the observability and operator
surface introduced by the new stage; it does not re-own implementation or the
full validation matrix of earlier subtasks.

## Acceptance Criteria

1. Goal status/progress reports shared-preplan status, planned and total
   subtask counts, the current planning subtask, and a concise blocked or
   resumable reason without returning raw preplan/plan payloads by default.
2. Goal lifecycle telemetry records exactly one goal-level discovery/preplan
   execution, per-subtask plan checkpoint completion, resume/reuse, hard-reset
   invalidation, and child hydration with stable attribution.
3. Hydrated child phases are distinguishable from child-agent-executed phases
   and do not double-count the shared preplan or saved plans as new child
   duration/token executions.
4. Standalone task telemetry remains unchanged and continues to attribute its
   directly executed preplan and plan phases to that standalone workflow.
5. CLI/MCP/status mapping tests cover fresh preparation, partial resume,
   planning block, all-prepared, hydrated child, and malformed durable-state
   failures.
6. `skills/bill-feature-goal/content.md`, relevant runtime skill guidance, and
   `runtime-kotlin/ARCHITECTURE.md` document one goal-level preplan, distinct
   per-subtask plans, database checkpoint/hydration, hard-reset invalidation,
   immutable reuse, and the unchanged standalone-task boundary.
7. Any governed skill-source or generated support change is validated and
   installed through `./install.sh`; generated wrappers or support pointers are
   not committed.
8. The maintainer validation suite in the parent spec passes after the
   observability and documentation changes.

## Non-Goals

- Do not redesign planning persistence or child hydration.
- Do not add raw planning payloads to default status responses.
- Do not alter standalone feature-task phase behavior.
- Do not create a catch-all audit-remediation or broad test-only phase.

## Dependency Notes

Depends on subtasks 1 through 3 so status and telemetry describe the final
preparation and hydration lifecycle.

## Validation Strategy

- Add focused status/telemetry contract, mapper, and adapter tests.
- Validate governed skill content and rendered install output where changed.
- Run the parent spec's maintainer validation commands.

## Next Path

After completion, `skill-bill goal SKILL-128` should proceed to normal goal
finalization and parent PR creation.
