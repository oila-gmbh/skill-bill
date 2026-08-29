# Subtask 1 — Discrete Gradle gate findings in collect-all parsing

## Scope

Extend collect-all finding extraction so common Gradle gate failures that today
collapse to `unparseable_gate_failure` become separate `ValidationGateFinding`
rows. Touch `FileSystemValidationGateRunner`, shared stdout parsers, and tests
only. Do not add the triage agent turn (subtask 2) and do not change repair-turn
limits or coordinator cycle shape beyond handing through richer finding sets.

### In scope parsers

- `:projectHealth` / dependency-analysis `incorrectConfiguration` output
  (module path, dependency coordinate, required `api` vs `implementation`).
- `:architectureCheck` forbidden project dependency messages.
- Residual multi-failure `BUILD FAILED` blocks: one finding per
  `Execution failed for task ':…'` when no finer parser already emitted a row
  for that task/output.

### Unchanged

- Compiler `e:` diagnostics, detekt XML, JUnit XML, spotless/ktlint stdout
  line parsers.
- `unparseable_gate_failure` only when all parsers return empty on a failed run.
- Validate/build coordinator repair loop (three turns, verify gate after each).

## Acceptance Criteria

1. A fixture gate stdout containing both `:harness-cursor:projectHealth`
   incorrect-configuration advice and `:architecture-tests:architectureCheck`
   forbidden dependency text yields **two or more** discrete findings with distinct
   `ruleOrTestId` values, and **no** `unparseable_gate_failure`.
2. A fixture containing only a `:projectHealth` failure yields at least one
   discrete finding identifying the module and the configuration mismatch.
3. A fixture where compiler diagnostics already parse does not regress: compiler
   findings are preserved and `unparseable_gate_failure` is not added.
4. A fixture where only JUnit/detekt artifacts parse behaves as today.
5. `unparseable_gate_failure` is emitted only when the run failed and every
   parser source returned empty.
6. Finding identity remains `module|ruleOrTestId|message|location`; duplicate
   lines dedupe by existing identity logic.
7. `FileSystemValidationGateRunnerTest` (or successor) adds focused cases for
   each new parser branch; existing COLLECT_ALL tests stay green.
8. No triage-turn scheduling, prompt, or coordinator changes land in this
   subtask.

## Non-Goals

- Agent triage turn before repair (subtask 2).
- Pack schema bumps unless a declaration hook is strictly required (prefer
  stdout parsing).
- Reintroducing substantiation receipts or repair-plan proof gates.
- Changing validate repair allowed tasks or SKILL-198 repair-window rules.

## Dependency Notes

None. Subtask 2 depends on this subtask.

## Validation Strategy

Unit tests with checked-in stdout fixtures for projectHealth, architectureCheck,
and multi-task failure summaries. Regression: SKILL-192-style compiler+JUnit
union fixture still passes. Module-scoped `./gradlew :runtime-infra-fs:test`
(or project-equivalent) before commit.

## Next Path

Subtask 2 adds briefing-only triage when parsing still yields only
`unparseable_gate_failure`.
