# SKILL-173 Subtask 1 - Thread validation_depth and assign it in GoalRunner

Parent spec: [.feature-specs/SKILL-173-goal-validate-build-only-until-last/spec.md](spec.md)
Issue key: SKILL-173

## Scope

Introduce `validation_depth` (`build_only` | `full`) on goal-continuation plumbing and have
`GoalRunner` stamp the correct value when launching each child from the decomposition
manifest.

Today `SkillRunGoalContinuationContext` / `FeatureTaskRuntimeGoalContinuationContext` carry
identity, `suppressPr`, review, and add-on fields but no validate depth. Every child therefore
gets the same full Phase 6 brief. This subtask adds the depth field end-to-end (models, CLI
parse, command builders / env) and computes it at launch:

- `fullTarget` = last subtask in manifest array order whose status is not `skipped`
- current subtask → `full` iff its id equals `fullTarget.id`, else `build_only`
- single non-skipped subtask → always `full`
- absent depth on legacy / non-goal launches → default `full`

Do not change validate prompt text or `validation_request` projection in this subtask — that is
subtask 2. After this lands, depth is present and correct on the continuation; the validate
phase still behaves as today until subtask 2 consumes it.

Primary files:

- `runtime-ports/.../AgentRunLauncherModels.kt` (`SkillRunGoalContinuationContext`)
- `runtime-application/.../FeatureTaskRuntimeRunModels.kt`
  (`FeatureTaskRuntimeGoalContinuationContext`)
- `runtime-application/.../goalrunner/GoalRunner.kt` (`goalContinuationContext`)
- `runtime-infra-fs/.../AgentRunCommandBuilders.kt`
- `runtime-cli/.../FeatureTaskRuntimeCliCommands.kt`

## Acceptance Criteria

1. Goal-continuation models expose `validation_depth` with wire values `build_only` and
   `full`, defaulting to `full` when omitted.
2. `GoalRunner` stamps `build_only` for every non-skipped subtask that is not the last
   non-skipped entry in manifest array order, and stamps `full` for that last entry (and for
   any single-subtask goal).
3. When the ordinal-last subtask is `skipped`, the previous last non-skipped entry receives
   `full`.
4. CLI / command-builder round-trip preserves the stamped depth on launch and resume.
5. Non-goal feature-task runs remain unaffected (no continuation → full validate path as
   today).
6. Unit tests cover the three-subtask depth sequence, single-subtask `full`, and skipped-last
   promotion.
7. `./gradlew build -x sourcesJar` and `detekt` pass for the touched modules.

## Non-Goals

- No change to validate phase directives, briefing assembly, or `validation_request`
  projection (subtask 2).
- No `bill-code-check` light mode.
- No `finalizeGoal` re-validate.
- No change to phase DAG / `phasesFor` truncation.

## Dependency Notes

Standalone. Subtask 2 depends on this field existing and being stamped correctly.

## Validation Strategy

1. GoalRunner unit tests for depth selection (3-subtask, 1-subtask, last skipped).
2. CLI / launcher tests that parse and emit the depth flag/env.
3. `(cd runtime-kotlin && ./gradlew :runtime-application:test :runtime-cli:test :runtime-infra-fs:test detekt)`.
4. `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.

## Next Path

Subtask 2 — make the validate phase honor `build_only` vs `full`.
