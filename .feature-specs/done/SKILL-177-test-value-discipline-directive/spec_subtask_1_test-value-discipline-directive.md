# SKILL-177 · Subtask 1 — Test-value discipline directive for plan and mutating phases

## Scope

Add a write-time test-value discipline counterpart to the existing
`minimalismDisciplineDirective`, so plan / implement / implement_fix agents in any
target repository get the same high-value test bar that `AGENTS.md` and
`bill-unit-test-value-check` already enforce at review time in this repository.

Edit only the prompt-composition seam:

- Add `testValueDisciplineDirective(phaseId)` in
  `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptDirectives.kt`.
  Gate on plan, implement, and implement_fix with a dedicated predicate (do not reuse
  `isMutatingPhase` alone — plan is not mutating). Return `""` for every other phase.
- Compose it in `FeatureTaskRuntimePhasePromptComposer.compose` immediately adjacent to
  `minimalismDisciplineDirective(...)`, without changing the rendered content or relative
  order of any existing directive section.
- Render the six contracted elements from the parent directive text (title + substance are
  contract; polish wording if needed): nameable realistic bug before writing; critical-path
  concentration; behavior-at-boundaries / no structure-coupled tests; one strong test per
  rule or branch; planning `test_obligations` only for behaviors that pass the bar, with an
  empty list a valid outcome; never remove/weaken real-bug regression coverage or treat
  governed parity / validator-backed rules as omission candidates.
- Extend the existing prompt-composition tests that pin `minimalismDisciplineDirective`
  and phase briefing content: assert presence (title + six elements) for plan, implement,
  and implement_fix; assert absence for preplan, review, audit, validate, and
  write_history.

Work from current `main` (SKILL-175 already merged). Prompt-only: no schema, contract
version, receipt field, or persisted-artifact changes; leave validate-phase test execution
untouched.

## Acceptance Criteria

1. A `testValueDisciplineDirective(phaseId)` function in `FeatureTaskRuntimePhasePromptDirectives.kt` renders a titled test-value discipline section for the plan, implement, and implement_fix phases and returns an empty string for every other phase.
2. `FeatureTaskRuntimePhasePromptComposer.compose` includes the new directive adjacent to `minimalismDisciplineDirective`, and the rendered content and relative order of every existing directive section are unchanged for all phases.
3. The rendered directive carries all six elements of the parent directive text: the nameable-bug requirement, the critical-path concentration list, the behavior-at-boundaries prohibition on structure-coupled tests, the one-test-per-rule rule, the planning-specific `test_obligations` guidance including that an empty list is a valid outcome, and the regression and governed-contract carve-outs.
4. The carve-outs explicitly protect regression tests tied to real past bugs and skill-bill's own governed parity tests and validator-backed rules, consistent with the minimalism directive's never-simplify-away list.
5. Prompt composition tests assert the directive is present in plan, implement, and implement_fix briefings and absent from preplan, review, audit, validate, and write_history briefings; the presence assertions pin the section title and the six elements, not the full prose.
6. The change is prompt-only: no schema, contract-version constant, receipt field, or persisted-artifact change, and the validate phase's test-execution behavior is untouched.
7. `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)` pass.

## Non-Goals

- No hard test-count caps, coverage thresholds, or deletion sweeps of existing tests.
- No changes to the review, audit, or validate phase policies.
- No changes to `bill-unit-test-value-check`, `bill-feature-verify`, or `AGENTS.md`.
- No skill-bill-v2 prompt changes.

## Dependency Notes

- None. Single subtask; base branch is `main` with SKILL-175 already landed.

## Validation Strategy

- Prompt composition unit tests alongside the existing directive tests.
- Phase-absence assertions are as load-bearing as presence.
- Full repository gate (`skill-bill validate` and `./gradlew check`) as the final check.

## Next Path

`skill-bill goal SKILL-177`
