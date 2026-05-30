# SKILL-57 Durable Progress Contract - Subtask 1: Progress State Foundation

Status: Draft
Parent spec: [.feature-specs/SKILL-57-durable-progress-contract/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 1 of 3
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Establish durable workflow-progress state primitives and runtime-boundary validation so progress events can be persisted and read independently of step-status mutations.

## Scope

In scope:

- Add the durable progress-event model to workflow state contracts with bounded retained history semantics (newest events retained, stable ordering).
- Extend persistence seams (ports + infra implementations) so progress events survive process exit and are queryable by workflow id.
- Add runtime-boundary validation and typed errors for malformed progress writes:
  - missing `workflow_id`
  - invalid `step_id`
  - invalid `attempt_count`
  - blank `message`
  - invalid `kind`
- Keep existing open/update/get/resume/continue behavior unchanged when progress events are absent.

Out of scope:

- Workflow-engine monotonic loop-back transition policy.
- CLI/MCP-facing progress write/read surfaces.
- Parent-runner status-display behavior.

## Acceptance Criteria

- 1. A workflow can record progress events independently of step status changes.
- 2. Progress events survive process exit and can be read by workflow id.
- 3. Writing progress does not mark a workflow or step complete, failed, or blocked.
- 4. Progress events are validated at the runtime boundary with typed errors for missing `workflow_id`, invalid `step_id`, invalid `attempt_count`, blank `message`, and invalid `kind`.
- 5. Progress history is bounded; when the limit is exceeded, the newest events are retained and ordering remains stable.
- 6. Existing workflow open/update/get/resume/continue behavior is unchanged when no progress events exist.

## Non-goals

- Emitting progress events from `bill-feature-implement` prompts.
- Goal-runner UI/supervision policy changes.
- Real-agent compatibility paths.

## Dependencies

- None.

## Validation Strategy

- `bill-quality-check`
- Targeted checks during implementation:
  - `(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-infra-sqlite:test :runtime-infra-fs:test)`

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-57-durable-progress-contract/spec_subtask_1_progress-state-foundation.md`.
