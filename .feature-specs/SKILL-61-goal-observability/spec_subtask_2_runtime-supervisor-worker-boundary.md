---
status: Pending
---

# SKILL-61 Subtask 2 - Runtime Supervisor + Worker Boundary

Parent spec: [.feature-specs/SKILL-61-goal-observability/spec.md](./spec.md)
Issue key: SKILL-61

## Scope

Implement the runtime-side production of observability events and codify the
worker boundary: implementation agents execute assigned leaves and may request
more work, but the runtime supervisor owns spawning, queuing, blocking, resume
checkpoints, and terminal reconciliation.

This subtask should make nested subagents unnecessary for correctness. If an
agent-provider supports native subthread inspection, it can remain optional
debug metadata only.

## Acceptance Criteria

1. Goal runtime emits observability events at meaningful lifecycle points:
   subtask start, phase change, heartbeat, file activity, worker output
   summary, block, resume, completion, and failure.
2. Runtime-owned state remains the source of truth for active subtask, workflow
   step, resume checkpoint, and terminal status.
3. Worker-emitted subtask requests have a structured parse path and typed
   outcomes: accepted, queued, rejected, or requires operator confirmation.
4. Accepted additional work becomes runtime-visible sibling work rather than
   hidden nested child state.
5. Resume after interruption preserves or reconciles the latest observability
   event for the active subtask.
6. Reset/completion clears or marks stale active observability state so status
   cannot report a dead active worker.
7. Existing goal reset/status/completion behavior from SKILL-58 does not
   regress.

## Non-Goals

- Do not implement dynamic decomposition insertion unless explicitly needed for
  structured subtask request acceptance.
- Do not make model-generated progress text authoritative over runtime state.
- Do not change the decomposition manifest schema unless the contract requires
  it and is covered by the schema-version process.

## Dependency Notes

Depends on: 1
Consumes the event contract and persistence decision from subtask 1.

## Validation Strategy

Add workflow/runtime tests for event emission, resume reconciliation, reset
cleanup, terminal cleanup, and structured worker subtask request outcomes.

## Next Path

Run bill-feature-task on spec_subtask_3_cli-watch-status-diff-ux.md.

## Spec Path

.feature-specs/SKILL-61-goal-observability/spec_subtask_2_runtime-supervisor-worker-boundary.md
