# Subtask 1 — First-class `build` phase and pack build command

## Scope

Add `build` as a real feature-task-runtime phase and declare the pack command
it runs. Wire the phase into the workflow definition, phase-output / handoff
contracts, runtime-owned gate execution (discover → repair → confirm on the
build argv), and a `build_receipt` projection. Do not yet change which goal
children enter `build` vs `validate` — default path stays `review → validate`
so standalone and existing goal children remain safe until subtask 2 routes.

## Acceptance Criteria

1. Phase id `build` exists in `FeatureTaskRuntimePhaseWorkflowDefinition` with
   dependencies and successors consistent with sitting after a clean `review`
   and before `write_history` on the path that selects it.
2. Platform pack schema (contract bump as required) declares a build command
   under `validation_gate`; Kotlin and KMP packs set a compile/buildability
   argv that is not the collect-all full gate.
3. Runtime-owned build gate runs that pack command, surfaces typed findings,
   supports one repair session and one confirmation run, and does not invoke
   `collect_all_full_gate_command`.
4. Settled build output produces a `build_receipt` (or equivalent named
   projection) that schema-validates; consumers in this subtask may still
   require `validation_receipt` — wiring either-or is subtask 2.
5. Phase prompts and AGENTS/runtime docs describe `build` as compile/build
   proof only: no suite tests, no full check, no substitute agent-run gate.
6. Focused tests cover schema parity, pack parse of the new field, and a build
   gate selection that never resolves to the full collect-all argv.

## Non-Goals

- GoalRunner last-vs-non-last routing (subtask 2).
- Making `write_history` / `commit_push` accept build receipts (subtask 2).
- Changing validate's collect-all behavior.

## Dependency Notes

None. Lands the contract and phase machinery first.

## Validation Strategy

Pack schema parity tests; phase-definition / transition tests for the new
phase id; build-gate unit tests asserting argv and receipt shape. Full
`./gradlew check --continue` before commit.

## Next Path

Subtask 2 stamps selection on goal continuation and routes children.
