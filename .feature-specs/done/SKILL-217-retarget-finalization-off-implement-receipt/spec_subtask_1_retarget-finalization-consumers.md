# SKILL-217 · Subtask 1 — Retarget finalization consumers onto phase_prose

## Scope

Stop finalization from decoding implement and plan `produced_outputs` as the
old receipt / executable_plan. Deliver those producers as `phase_prose`. Keep
checkpoint working-tree inventory runtime-owned.

In scope:

- Replace implement-produced `validation_request`, `boundary_candidates`,
  `commit_request`, and `pr_request` field lists that still name receipt keys
  with `phaseProseDeclaration` where the next agent needs the words, plus
  runtime-owned checkpoint / identity fields where Kotlin already measures the
  tree.
- Add plan `phaseProseDeclaration` on validate and build.
- Add implement `phaseProseDeclaration` on write_history, commit_push, and pr.
- Stop `finalizationProjectionContext` from reading implement
  `changed_paths` / `completed_task_ids` / `tests_*` / `deviations` and from
  reading plan `validation_strategy` / `tasks`.
- Drop `pr_request` fields `completed_task_ids`, `tests_added`,
  `tests_updated`, `deviations`.
- Drop `commit_request` exclusion diffs that compare leftover implement
  `changed_paths` to the tree.
- Stop deriving `validation_request.required_checks` from decoded plan fields.
- Update `FeatureTaskRuntimePhaseWorkflowDefinitionTest` and handoff /
  runner coverage so a value-only implement (and plan) still launches
  finalization and the briefing carries `value`.
- Delete tests that only assert the retired receipt field names on those
  consumers.

## Acceptance Criteria

1. A completed implement whose `produced_outputs` is only `{ "value": "…" }`
   launches validate, build, write_history, commit_push, and pr.
2. Write_history, commit_push, and pr briefings contain that implement `value`
   string. Optional `prompt` is included when present.
3. Validate and build briefings contain plan `value`. Optional `prompt` is
   included when present.
4. Changed-path inventory on those consumers comes from the repository
   checkpoint working tree, not from implement or plan sibling keys or from
   parsing `value`.
5. `pr_request` no longer declares `completed_task_ids`, `tests_added`,
   `tests_updated`, or `deviations`.
6. Kotlin does not fill `required_exclusions` from leftover implement
   `changed_paths`, and does not fill `required_checks` from decoded plan
   `validation_strategy` or `tasks`.
7. `finalizationProjectionContext` and `prRequestProjection` do not parse
   implement or plan `value`.
8. No new handoff contract id. Consumers reuse
   `feature_task_runtime.phase_prose`.

## Non-Goals

- Putting finalization phases on `PhaseOutput`.
- Changing commit `message` / `commit_sha` ownership.
- Changing validation or build receipt minting.
- Changing census verify/fix I/O.

## Dependency Notes

Depends on SKILL-213 (implement `phase_prose`) and SKILL-212 (plan
`phase_prose` and `phaseProseDeclaration`), landed on `main`. Audit already
consumes implement through that helper. No new dependency outside the
repository.

## Validation Strategy

- `./gradlew compileKotlin` from `runtime-kotlin` for buildability, then the
  pack collect-all gate for the full check.
- Handoff or runner test: value-only implement still launches each
  finalization consumer; implement `value` appears on write_history,
  commit_push, and pr briefings; plan `value` appears on validate and build
  briefings.
- Definition test: `pr_request` field list no longer includes the retired
  receipt names.
- No test may assert that finalization reads `completed_task_ids` or plan
  `tasks` from `produced_outputs`. Removing those assertions is part of the
  change.

## Next Path

Parent next path. No further producer I/O conversion.
