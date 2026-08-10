# SKILL-177 — Test-value discipline directive for planning and mutating phases

Status: Pending

## Intended Outcome

Feature-task runs write few, high-value tests by construction instead of mirroring production code 1:1.

The runtime already ships one universal write-time discipline: `minimalismDisciplineDirective` renders "Minimalism discipline (reuse before write)" into every mutating-phase briefing, so agents in any target repository get the same anti-over-engineering bar for production code. There is no counterpart for tests. Test volume is decided implicitly — the plan phase emits `test_obligations` per task with no guidance on what deserves an obligation, and the implement phase writes whatever the plan obligated plus whatever it deems prudent. The observed result is near 1:1 test:code ratios dominated by change-detectors: tests that assert what the code does rather than catch a realistic regression, and that cost reasoning tokens on every future change without buying safety.

A review-time counterpart already exists — `bill-unit-test-value-check` (tightened 2026-08-09: burden of proof on the test, delete-by-default dispositions, criticality weighting, redundancy and refactor-coupling flags) — and this repository's `AGENTS.md` carries a `## Testing` section with the same bar. Neither reaches write time in arbitrary user repositories where the feature-task runtime runs. Catching bloat at review still pays for writing it first; the bar must be in the phase briefing that produces the tests.

## Background — where the directive lands

- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptDirectives.kt` is the sole runtime-owned source for mutating-phase briefing text. `minimalismDisciplineDirective(phaseId)` gates on `FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)` and is the structural precedent for this change.
- `FeatureTaskRuntimePhasePromptComposer.compose` assembles the prompt; the minimalism block is composed at `FeatureTaskRuntimePhasePromptComposer.kt:56`. The new directive composes adjacent to it.
- The plan-phase task schema in `phaseDirectives[PHASE_PLAN]` carries `test_obligations` per task, and the implementation receipt reports `tests_added` / `tests_updated`. Test count is therefore largely decided at planning time, so the directive must render for the plan phase as well — `isMutatingPhase` alone does not cover it, and the new directive needs its own phase predicate covering plan, implement, and implement_fix.
- The audit phase already refuses test-adequacy findings ("Never report test adequacy, coverage, fixtures, assertions, or other test-only concerns as audit gaps") and the validate phase owns test execution. Neither changes.

## Directive text

The authoritative draft the implementation should render (title and bullet substance are contract; exact wording may be polished during implementation):

```
## Test-value discipline (every test must earn its cost)
Tests are a recurring cost: every future change to the code they touch pays for them in
maintenance and reasoning tokens. Write few, high-value tests; never mirror code 1:1 with tests.
- Before writing a test, name the realistic bug it would catch — a concrete wrong behavior that
  fails this test while the rest of the suite passes. If you cannot, do not write the test.
- Concentrate coverage on critical paths: money and quantities, data integrity and persistence
  atomicity, auth and tenant isolation, external contracts and serialization, concurrency and
  recovery, irreversible side effects. Trivial glue on non-critical paths needs no test; say so
  instead of writing one.
- Assert observable behavior at boundaries, never implementation structure: no mock-interaction
  verification without an outcome assertion, no call-ordering assertions, no implementation
  logic duplicated inside the test.
- One strong test per rule or branch; no sibling tests re-covering the same branch with
  different literals.
- When planning, emit test_obligations only for behaviors that pass this bar, each tied to an
  acceptance criterion or a named realistic bug; an empty test_obligations list is a valid
  outcome for a task.
- Never remove or weaken regression coverage tied to a real past bug, and never treat governed
  parity tests or validator-backed rules as omission candidates — the minimalism carve-outs
  apply to tests too.
```

## Acceptance Criteria

1. A `testValueDisciplineDirective(phaseId)` function in `FeatureTaskRuntimePhasePromptDirectives.kt` renders a titled test-value discipline section for the plan, implement, and implement_fix phases and returns an empty string for every other phase.
2. `FeatureTaskRuntimePhasePromptComposer.compose` includes the new directive adjacent to `minimalismDisciplineDirective`, and the rendered content and relative order of every existing directive section are unchanged for all phases.
3. The rendered directive carries all six elements of the directive text above: the nameable-bug requirement, the critical-path concentration list, the behavior-at-boundaries prohibition on structure-coupled tests, the one-test-per-rule rule, the planning-specific `test_obligations` guidance including that an empty list is a valid outcome, and the regression and governed-contract carve-outs.
4. The carve-outs explicitly protect regression tests tied to real past bugs and skill-bill's own governed parity tests and validator-backed rules, consistent with the minimalism directive's never-simplify-away list.
5. Prompt composition tests assert the directive is present in plan, implement, and implement_fix briefings and absent from preplan, review, audit, validate, and write_history briefings; the presence assertions pin the section title and the six elements, not the full prose.
6. The change is prompt-only: no schema, contract-version constant, receipt field, or persisted-artifact change, and the validate phase's test-execution behavior is untouched.
7. `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)` pass.

## Constraints

- Word the directive as a value bar, never a numeric cap: it must compose with target repositories whose own `AGENTS.md` carries stricter or more specific testing conventions, which the phases already read.
- The directive text lives only in `FeatureTaskRuntimePhasePromptDirectives.kt`, matching the minimalism precedent of a single runtime-owned source.
- Base on `main` after SKILL-175 merges: `FeatureTaskRuntimePhasePromptDirectives.kt` and its tests are modified on the SKILL-175 branch, and starting earlier guarantees conflicts in the exact seam this task edits.

## Non-Goals

- No hard test-count caps, coverage thresholds, or deletion sweeps of existing tests.
- No changes to the review, audit, or validate phase policies; audit's refusal of test-adequacy gaps and validate's ownership of test execution stand as-is.
- No changes to `bill-unit-test-value-check`, `bill-feature-verify`, or `AGENTS.md` — the review-time gate and the repo-level bar already landed on 2026-08-09.
- No skill-bill-v2 changes; v2's prompt architecture needs its own equivalent and is tracked separately.

## Validation Strategy

- Prompt composition unit tests alongside the existing directive tests (the files that pin `minimalismDisciplineDirective` and phase briefing content are the pattern to follow).
- Phase-absence assertions are as load-bearing as presence: the directive leaking into review or audit briefings would instruct evaluators, not producers.
- Full repository gate as the final check.

## Next Path

Prepare and dispatch via `bill-feature` once SKILL-175 has merged to `main`.
