---
status: Pending
---

# SKILL-66 Subtask 3 - Goal Runner Runtime Emission

Parent spec: [.feature-specs/SKILL-66-feature-goal-telemetry/spec.md](./spec.md)
Issue key: SKILL-66

## Scope

Emit the three goal telemetry events from the goal-runner application layer at
the lifecycle boundaries the runtime already owns: run start, each subtask's
terminal transition, and the run's terminal outcome (completed or blocked).
Timestamps, durations, and counts come from the runtime clock and the runner's
own state — never from agent-reported values. Resolve and record the two
emission-semantics open questions from the parent spec (resume double-count
prevention; per-segment `goal_finished` on blocked outcomes).

This subtask owns emission only. The contract (Subtask 1) and store
(Subtask 2) exist; no stats surface is added here (Subtask 4).

## Acceptance Criteria

1. `GoalRunner` (or a dedicated collaborator beside
   `GoalRunnerObservabilityEmitter` / `GoalRunnerLedgerRecorder`, following
   that established decomposition style) emits:
   - exactly one `goal_started` per run segment, at loop start, with
     `resumed: true` when the segment resumes prior work;
   - exactly one `goal_subtask_finished` per subtask reaching terminal status
     (`complete`/`blocked`/`skipped`) within the current segment — never for
     subtasks completed in earlier segments;
   - exactly one `goal_finished` per run segment at terminal outcome, with
     `status: "completed"` or `"blocked"` and subtask counts by terminal
     status.
2. All timestamps/durations derive from the runtime clock seam already used by
   the runner; no agent-supplied timing enters any payload.
3. Emission goes through the lifecycle-telemetry service/repository seam from
   Subtask 2; the application layer gains no MCP/Clikt/JDBC dependency.
4. A telemetry write failure fails the run loudly with a typed error (parent
   AC5); it is not swallowed, retried silently, or downgraded to a log line.
   The decision and rationale are recorded in the relevant
   `agent/decisions.md`.
5. Goal runtime behavior is otherwise unchanged: CLI output, manifest updates,
   observability/progress artifacts, and blocking semantics are
   byte-equivalent for runs that do not hit telemetry failures (parent AC8).
6. The resume-semantics resolution (dedupe by
   `(issue_key, subtask_id, workflow_id)`; per-segment `goal_finished`) is
   implemented and documented in the parent spec's Open Questions section
   (marked resolved) and reflected in stats expectations for Subtask 4.
7. Tests: application tests with a fake launcher and fake clock asserting
   exact event counts and payload contents for: a clean completed run, a run
   blocked mid-subtask, a resumed run completing remaining subtasks (no
   double-count), a run with a skipped subtask, and a telemetry write failure
   (loud-fail).

## Non-Goals

- No stats aggregation, MCP tool, or CLI surface (Subtask 4).
- No remote-stats mapping (Subtask 5).
- No changes to goal-observability/progress event emission or schemas.
- No emission for the experimental `feature-task-runtime` family.

## Dependency Notes

Depends on: Subtask 1 (payload contracts) and Subtask 2 (persistence seam).

Provides the recorded data Subtask 4 aggregates.

## Validation Strategy

Application tests with fake launcher + fake clock covering all run-outcome
shapes and the loud-fail path; `RuntimeArchitectureTest` continues to pass;
manual smoke: one real `skill-bill goal` run records the expected three-event
trace in the local store.

## Next Path

Run bill-feature-task on spec_subtask_4_goal-stats-mcp-and-cli-surface.md.

## Spec Path

.feature-specs/SKILL-66-feature-goal-telemetry/spec_subtask_3_goal-runner-runtime-emission.md
