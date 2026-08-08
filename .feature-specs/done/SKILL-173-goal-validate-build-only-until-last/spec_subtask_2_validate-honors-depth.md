# SKILL-173 Subtask 2 - Validate phase honors build_only vs full

Parent spec: [.feature-specs/SKILL-173-goal-validate-build-only-until-last/spec.md](spec.md)
Issue key: SKILL-173

## Scope

Make Phase 6 (`validate`) consume the goal-continuation `validation_depth` stamped by
subtask 1.

Under `build_only`:

- Validate directive (or a goal-continuation validate addendum parallel to
  `goalContinuationDirective`) tells the agent to prove compile/buildability only, fix only
  compile/build failures, and forbid tests, detekt, spotless, lint, dependency scanners, and
  the full `bill-code-check` gate.
- Projected `validation_request.required_checks` must not forward plan `test_obligations`;
  replace with a compile/buildability obligation. Keep receipt fields unchanged
  (`validation_status`, `checks`, `repository_checkpoint`).

Under `full` (and any non-goal run): keep today's Phase 6 directive and projection behavior.

Primary files:

- `runtime-application/.../FeatureTaskRuntimePhasePromptDirectives.kt`
- `runtime-application/.../FeatureTaskRuntimePhasePromptComposer.kt` / briefing assembler as
  needed to thread depth into the validate briefing
- `runtime-domain/.../FeatureTaskRuntimeHandoffProjectionValidator.kt`
  (`finalizationProjectionValues` / `requiredChecks`)

## Acceptance Criteria

1. A goal-continuation validate briefing with `validation_depth=build_only` carries the
   compile-only directive and does not instruct a full repository validation gate or test
   execution.
2. The same briefing's projected `validation_request` omits plan `test_obligations` from
   `required_checks` and includes a compile/buildability obligation instead.
3. A `validation_depth=full` (or absent / non-goal) validate briefing matches today's
   behavior: run implement-written tests and the repository validation gate.
4. `validation_receipt` field set is unchanged; consumers `write_history` and `commit_push`
   still assemble without a schema bump.
5. Tests cover `build_only` vs `full` briefing/projection differences.
6. `./gradlew build -x sourcesJar` and `detekt` pass for the touched modules.

## Non-Goals

- No change to how `GoalRunner` chooses depth (owned by subtask 1).
- No `bill-code-check` light mode or pack schema change.
- No parent-level validate in `finalizeGoal`.
- No change to implement-phase test-writing rules.

## Dependency Notes

Depends on subtask 1: `validation_depth` must exist on the continuation context and be
stamped at launch. Manifest dependency: `{ subtask_id: 1 }`.

## Validation Strategy

1. Prompt/composer or briefing tests asserting `build_only` vs `full` directive and
   `required_checks` contents.
2. Projection tests that `test_obligations` are dropped under `build_only` and retained under
   `full`.
3. `(cd runtime-kotlin && ./gradlew :runtime-application:test :runtime-domain:test detekt)`.
4. `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.

## Next Path

Optional follow-up: first-class `bill-code-check` compile-only mode for stronger enforcement
than prompt + narrowed `required_checks`.
