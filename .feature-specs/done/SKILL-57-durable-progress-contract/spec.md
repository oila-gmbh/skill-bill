# SKILL-57 durable-progress-contract

Status: In Progress
Issue key: SKILL-57
Feature size: MEDIUM
Rollout needed: false

## Sources

- Briefing spec source: `.feature-specs/SKILL-57-goal-runner-production-hardening/spec_subtask_1_durable-progress-contract.md`

## Acceptance Criteria

1. A workflow can record progress events independently of step status changes.
2. Progress events survive process exit and can be read by workflow id.
3. Writing progress does not mark a workflow or step complete, failed, or blocked.
4. Progress events are validated at the runtime boundary with typed errors for missing `workflow_id`, invalid `step_id`, invalid `attempt_count`, blank message, and invalid `kind`.
5. Progress history is bounded; when the limit is exceeded, the newest events are retained and ordering remains stable.
6. Existing workflow open/update/get/resume/continue behavior is unchanged when no progress events exist.
7. Tests cover the new contract in `runtime-domain`, `runtime-application`, and any CLI/MCP seam added for progress writes.
8. `workflow get <workflow_id>` reliably returns active child workflows during a parent `bill-goal` run. If a row is missing, the error reports the db path, requested workflow id, and nearest matching rows by issue/subtask/session metadata when available.
9. Current-step updates are monotonic by default. A sequence like `commit_push -> finish -> audit -> finish` is impossible unless the `audit` transition is persisted as an intentional loop-back with a reason.
10. Tests cover stale/regressive updates, intentional loop-back updates, and status/progress reads after terminal `finish`.

## Consolidated Spec

# SKILL-57 Subtask 1 - Durable Workflow Progress Contract

Status: Draft
Parent spec: [.feature-specs/SKILL-57-goal-runner-production-hardening/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 1 of 4
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Add a durable, typed progress channel to workflow state so long-running child
agents can report liveness and task-level movement without pretending the
workflow has reached a terminal outcome.

## Scope

In scope:

- Define a typed progress event model with at least:
  - `workflow_id`
  - `step_id`
  - `attempt_count`
  - `source` (`orchestrator`, `phase_subagent`, `launcher`, or similar)
  - `kind` (`phase_started`, `task_started`, `task_completed`, `heartbeat`,
    `phase_completed`, `degraded_mode`, `stall_warning`)
  - `message`
  - monotonically increasing `sequence`
  - timestamp
- Add a workflow progress write/read seam in the runtime application layer.
- Persist progress events in durable workflow state without changing terminal
  status semantics.
- Make workflow reads authoritative and inspectable while a parent goal is
  running. `workflow get <workflow_id>` must keep resolving active child
  workflows by id, and missing-workflow diagnostics must include the db path and
  nearest relevant workflow rows.
- Define monotonic step-transition semantics for workflow updates. Moving
  `current_step_id` to an earlier step is allowed only when the update records
  explicit loop-back metadata; otherwise the update is rejected or ignored with
  a durable diagnostic.
- Bound retained progress history so workflow rows cannot grow without limit.
- Include progress events in resume/continue payloads only where useful:
  latest event in summary, bounded history for diagnostics.
- Add validation coverage for malformed events, sequence behavior, bounded
  retention, and non-terminal semantics.

Out of scope:

- Updating `bill-feature-implement` prompt content to emit events. That is
  subtask 2.
- Parent goal-runner display/status changes. That is subtask 3.
- Real-agent compatibility. That is subtask 4.

## Acceptance Criteria

1. A workflow can record progress events independently of step status changes.
2. Progress events survive process exit and can be read by workflow id.
3. Writing progress does not mark a workflow or step complete, failed, or
   blocked.
4. Progress events are validated at the runtime boundary with typed errors for
   missing `workflow_id`, invalid `step_id`, invalid `attempt_count`, blank
   message, and invalid `kind`.
5. Progress history is bounded; when the limit is exceeded, the newest events
   are retained and ordering remains stable.
6. Existing workflow open/update/get/resume/continue behavior is unchanged when
   no progress events exist.
7. Tests cover the new contract in `runtime-domain`, `runtime-application`, and
   any CLI/MCP seam added for progress writes.
8. `workflow get <workflow_id>` reliably returns active child workflows during a
   parent `bill-goal` run. If a row is missing, the error reports the db path,
   requested workflow id, and nearest matching rows by issue/subtask/session
   metadata when available.
9. Current-step updates are monotonic by default. A sequence like
   `commit_push -> finish -> audit -> finish` is impossible unless the `audit`
   transition is persisted as an intentional loop-back with a reason.
10. Tests cover stale/regressive updates, intentional loop-back updates, and
    status/progress reads after terminal `finish`.

## Validation

```bash
(cd runtime-kotlin && ./gradlew \
  :runtime-domain:test \
  :runtime-application:test \
  :runtime-mcp:test \
  :runtime-cli:test)
```
