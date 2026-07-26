---
status: Complete
---

# SKILL-85 Subtask 1 - Resume & State-Model Integrity

Parent spec: [.feature-specs/SKILL-85-runtime-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-85

## Scope

Make the runtime feature-task family maintain the shared workflow model it is
judged by, so resume works before any backward-edge loop is layered on top. Today
every runtime recorder write passes `stepUpdates = null` and patches only
`feature_task_runtime_*` keys (`FeatureTaskRuntimePhaseRecorder.kt:242-264`), so
the per-step `steps[]` array is permanently all-`pending` and the canonical
per-phase artifact keys that the runtime's own `requiredArtifactsByStep`
references are never written (`FeatureTaskRuntimePhaseWorkflowDefinition.kt:66-77`).
As a result the generic resume gate computes `canResume = false` for every phase
past `preplan` (`WorkflowEngine.kt:187-189`), the generic
`feature_task_runtime_workflow_continue`/`_resume` tools are structurally dead
(`WorkflowCliResultMappers.kt:215-220`), and step-based reconciliation is inert
(`GoalRunnerWorkflowStores.kt:1198-1208, 983-999`). This subtask closes that
class (the audit's C1-C5) so a process death mid-run resumes cleanly through the
sanctioned path rather than wedging.

This subtask follows the maintainer's rule: a run must be resumable unless there
is a hard blocker that is genuinely impossible to bypass. A missing canonical key
whose data exists in `feature_task_runtime_phase_records` is not such a blocker.

## Acceptance Criteria

1. The recorder advances the shared per-step `steps[]` model in lockstep with its
   private phase records: completing a phase marks that step `completed`, a
   running phase marks it `running`, a blocked phase marks it `blocked`. The
   coarse `current_step_id`/`workflow_status` it already maintains remain correct.
2. The generic resume gate resolves required-upstream presence for the runtime
   family from authoritative runtime state - either by writing the canonical
   per-phase artifact keys, or by a family-aware presence resolver that reads
   `feature_task_runtime_phase_records`. `resumeView` no longer reports a false
   `missing_artifacts`/`canResume = false` when the upstream output exists in the
   records. (Resolve the A-vs-B choice from the parent Open Questions during
   pre-planning.)
3. `feature_task_runtime_workflow_resume` reports accurate fields:
   `last_completed_step_id` reflects real completed phases, `can_resume` is true
   when the run is recoverable, and `resume_step_id` is the last incomplete
   phase.
4. `feature_task_runtime_workflow_continue` either drives the family forward or
   returns an honest, accurate status; it never emits a false
   "Cannot continue ... missing artifacts" error for a recoverable run.
5. Goal-runner reconciliation no longer depends on inert step heuristics for the
   runtime family: `blockedStepId` and the `terminalStatus` step-fallback operate
   on a truthful `steps[]`, or are made to read the runtime's records, so a
   crashed runtime row that lacks `goal_continuation_outcome` is reconciled
   correctly rather than mis-defaulting to `preplan`.
6. The declared terminal-summary artifact resolves to real persisted state:
   either a `pr` artifact is written, or `completedTerminalSummaryArtifact` and
   its consumers point at an artifact that actually exists
   (`FeatureTaskRuntimePhaseWorkflowDefinition.kt:96`).
7. The runner's existing resume-from-records path
   (`FeatureTaskRuntimeRunner.kt:88-99`, `recordToOutput`) continues to work and
   now agrees with the shared model; the two representations cannot diverge.
8. A regression test reproduces the original wedge: a runtime workflow with
   completed `preplan`/`plan` phase records but a dead process (no terminal
   outcome) reports `can_resume = true` and resumes at `implement` rather than
   blocking at `preplan`.
9. Architecture boundaries hold: any family-aware presence seam lives behind a
   domain-owned port/abstraction; no infra imports in `runtime-application`;
   `RuntimeArchitectureTest` passes.

## Non-Goals

- No backward edges or loops yet (Subtasks 2, 4, 5).
- No mutating-phase idempotency changes (Subtask 3).
- Do not modify the prose `IMPLEMENT` family's resume/continue behavior or
  goldens.
- Do not change per-phase output schemas (Subtask 4 owns the verdict fields).

## Dependency Notes

Depends on: none (foundation). Consumed by: all later subtasks - the cyclic
executor and both loops rely on correct resume and a truthful `steps[]`.

## Validation Strategy

Application persistence-port tests for `steps[]` advancement and resume-field
correctness; domain tests for the resume gate's presence resolution for the
runtime family; a focused regression test for the reproduced wedge (AC8);
reconciliation tests for a crashed runtime row; assertions that the prose family
is untouched; `RuntimeArchitectureTest` for boundaries.

## Next Path

Run bill-feature-task on spec_subtask_2_bounded-cyclic-phase-executor.md.

## Spec Path

.feature-specs/SKILL-85-runtime-remediation-loops/spec_subtask_1_resume-and-state-model-integrity.md
