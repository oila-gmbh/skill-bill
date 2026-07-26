# SKILL-57 Durable Progress Contract - Subtask 2: Engine Monotonic Transitions and Diagnostics

Status: Draft
Parent spec: [.feature-specs/SKILL-57-durable-progress-contract/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 2 of 3
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Apply workflow-engine and application-layer semantics so progress writes are non-terminal, current-step transitions are monotonic by default, and child-workflow retrieval remains authoritative with actionable missing-row diagnostics.

## Scope

In scope:

- Add workflow service/engine progress write-read behavior using the durable state from subtask 1.
- Enforce monotonic `current_step_id` updates by default.
- Allow loop-back only when update payload includes explicit intentional loop-back metadata and reason, and persist that reason durably.
- Reject or ignore stale/regressive updates that do not satisfy loop-back policy.
- Ensure `workflow get <workflow_id>` reliably resolves active child workflows during parent `bill-goal` runs.
- Enrich missing-workflow diagnostics with db path, requested workflow id, and nearest matching rows keyed by issue/subtask/session metadata when available.
- Keep status semantics unchanged: progress writes cannot mark workflow/step complete, failed, or blocked.

Out of scope:

- New CLI/MCP command/tool design beyond using existing engine maps.
- Prompt-level event emission for feature-implement workflows.
- Parent status-display redesign.

## Acceptance Criteria

- 3. Writing progress does not mark a workflow or step complete, failed, or blocked.
- 8. `workflow get <workflow_id>` reliably returns active child workflows during a parent `bill-goal` run. If a row is missing, the error reports the db path, requested workflow id, and nearest matching rows by issue/subtask/session metadata when available.
- 9. Current-step updates are monotonic by default. A sequence like `commit_push -> finish -> audit -> finish` is impossible unless the `audit` transition is persisted as an intentional loop-back with a reason.
- 6. Existing workflow open/update/get/resume/continue behavior is unchanged when no progress events exist.

## Non-goals

- Cross-surface display polishing for progress in parent UX.
- Real-agent policy compatibility.

## Dependencies

- Subtask 1 provides durable progress contract and persistence seams consumed here.

## Validation Strategy

- `bill-quality-check`
- Targeted checks during implementation:
  - `(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test)`

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-57-durable-progress-contract/spec_subtask_2_engine-monotonic-and-diagnostics.md`.
