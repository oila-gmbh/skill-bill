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

## Validation

```bash
(cd runtime-kotlin && ./gradlew \
  :runtime-domain:test \
  :runtime-application:test \
  :runtime-mcp:test \
  :runtime-cli:test)
```
