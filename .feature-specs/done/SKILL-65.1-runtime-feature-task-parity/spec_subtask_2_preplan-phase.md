# SKILL-65.1 · Subtask 2 — Pre-Planning Phase

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Add a `preplan` phase upstream of `plan`, mirroring `bill-feature-task` Step 2
(pre-planning subagent → `preplan_digest` artifact). The runtime currently jumps
straight to `plan`; the planning phase gets no pre-planning digest. This subtask
inserts `preplan` as the new first runnable phase and feeds its output to `plan`.

## Acceptance Criteria

1. `FeatureTaskRuntimePhaseWorkflowDefinition` declares `PHASE_PREPLAN = "preplan"`
   as the new initial phase (`defaultInitialStepId = PHASE_PREPLAN`), positioned
   before `plan` in `stepIds`, with a `stepLabels` entry, empty
   `requiredArtifactsByStep[PHASE_PREPLAN]`, and a `resumeActions` entry.
2. `requiredArtifactsByStep[PHASE_PLAN]` is updated so `plan` consumes the
   `preplan` output (`listOf(PHASE_PREPLAN)`), and the `plan` resume action and
   briefing reflect the new upstream.
3. `FeatureTaskRuntimePhasePromptComposer.phaseDirectives` has a `preplan`
   directive instructing the agent to produce a pre-planning digest (scope,
   affected boundaries, risks/unknowns, rollout need) without modifying repo
   files, emitting schema-valid `produced_outputs`.
4. The phase-output schema is unchanged and requires no contract-version bump
   (`produced_outputs` already accommodates the digest).
5. The runtime drives `preplan -> plan -> implement -> review -> audit ->
   validate`; `FeatureTaskRuntimeRunnerTest` and the phase-definition/status
   tests are updated for the new phase ordering, labels, and counts.
6. Resume deterministically skips a completed `preplan` from its durable record
   and restores the digest into the `plan` briefing.

## Non-Goals

- No size-conditional skipping of `preplan` (that lives in subtask 4).
- No change to the `plan` phase's internal behavior beyond consuming the digest.

## Dependency Notes

- Depends on: none (touches only the phase definition / composer / tests).
- Downstream: subtask 4 may make `preplan` ceremony size-conditional; subtask 5
  builds decomposition onto the `plan` phase that now consumes the digest.

## Validation Strategy

- Update `FeatureTaskRuntimePhaseWorkflowDefinitionTest` (six-phase stepIds,
  initial step, label, required-artifacts, resume action).
- Update `FeatureTaskRuntimePhasePromptComposerTest` (preplan directive + valid
  prompt/output contract) and `FeatureTaskRuntimeRunnerTest` (phase list).
- Update `FeatureTaskRuntimeStatusServiceTest` for counts/labels.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 3 — Post-Validate Phases](./spec_subtask_3_post-validate-history-commit-pr-phases.md),
or run `skill-bill goal SKILL-65.1`.
