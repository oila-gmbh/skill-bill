# SKILL-212 subtask 1. Replace executable_plan with a shared prose kit

## Scope

Generalize the SKILL-211 preplan prose wiring into a phase-neutral kit, then
point plan → implement at it. Keep the outer envelope. Drop the
`executable_plan` schema gate on this edge. Point audit and audit-gap
implement at the same kit. Stop deriving `plan_commitment` and stop closing
implement receipts against a gated plan task list. Adjust goal-planning
plan payload, `regenerate_plan`, decompose detection, and phase prompts so
they match the new handoff.

In scope:

- Extract one phase-output `$defs` shape (`value` required, `prompt`
  optional, extra keys allowed). Preplan and plan `allOf` branches `$ref`
  it. Do not copy the preplan `allOf` body.
- Rename `feature_task_runtime.preplan_prose` to
  `feature_task_runtime.phase_prose`. Keep projection names
  `${producingPhaseId}_prose`. One declaration helper (consumer +
  producer). One decoder. No `plan_prose` sibling.
- Remove `executable_plan` and `plan_commitment` from the
  planning-projections `oneOf` and bump that contract version
- `producedProjectionKindFor(plan)` returns null. Implement, audit, and
  `auditRemediationProjections` consume `phaseProseDeclaration` from plan,
  not `executablePlanDeclaration` / `planCommitmentDeclaration`
- Remove the implement → plan `regenerate_plan` consumer edge and the
  `PHASE_PLAN` entry in `REGENERATION_LOOP_ID_BY_PRODUCER`
- SKILL-150 completion gate: do not read planned task ids from a delivered
  `executable_plan`; keep `unresolved_items` and audit-gap repair-item
  closure
- Tighten `featureTaskRuntimeIsDecompositionPackage` so a leftover
  `mode: decompose` beside `value` is not a decompose stop; require
  `produced_outputs.decomposition_package`
- Goal-planning `plan_payload` write / hydrate / checkpoint follow the
  producer gate (null kind, no `executable_plan` demand)
- One shared `produced_outputs` JSON example for prose phases. Plan /
  implement directives and goal-continuation decompose wording
- Shared prose-handoff tests covering preplan → plan and plan → implement,
  plus preplan regression after the contract rename
- Delete `executable_plan` rejection siblings that no longer name a real
  bug, including empty `test_obligations` as a plan write-time rejection

## Acceptance Criteria

1. Existing `PhaseOutput` is the agent-authored shape of plan
   `produced_outputs`. There is no new payload type and no
   `plan_prose` contract.
2. A completed plan whose `produced_outputs` is only `{ "value": "…" }`
   advances to implement. The implement briefing contains that string.
3. Leftover `executable_plan` keys plus `value` still complete. The producer
   gate does not re-enter plan for digest-style schema violations.
4. Blank or missing `value` on `status: completed` retries or blocks plan.
5. Optional `prompt` appears in the implement briefing when present and is
   omitted cleanly when absent.
6. `producedProjectionKindFor("plan")` is null. Implement does not parse
   `feature_task_runtime.executable_plan`. The `regenerate_plan` consumer
   edge from implement is gone.
7. Audit briefings carry plan prose, not `plan_commitment`. Audit-gap
   implement receives the same prose, through the same
   `phase_prose` declaration helper.
8. A schema-valid `completed` implementation receipt is not rejected for
   failing to close gated plan task ids. `unresolved_items` and audit-gap
   repair-item closure still hold.
9. `{ "value": "…", "mode": "decompose" }` is a plan handoff, not a
   decomposition-package stop.
10. Plan and implement prompts recommend prose and share the preplan
    `produced_outputs` JSON example. They do not require a gated
    `executable_plan` JSON example.
11. Preplan and plan `$ref` one `$defs` produced-outputs shape. The handoff
    contract id is `feature_task_runtime.phase_prose` for both edges. A
    completed preplan with only a non-blank `value` still advances to plan.

## Non-Goals

- Rewiring the implement `implementation_receipt` or any later phase onto
  `PhaseOutput` in this subtask. The kit must make that a later matrix
  change.
- Renaming `AgentPhaseOutput.output` or `PhaseOutput`.
- Parsing task ids out of plan prose.
- Removing the standalone `decomposition_package` stop.
- Telemetry proxy stats for this edge.

## Dependency Notes

- Depends on: none (only subtask). SKILL-211 already shipped `PhaseOutput`
  and the preplan-shaped wiring this change generalizes.
- Unblocks: later skills that add a matrix row on `phase_prose` (implement
  → audit first) without a new contract.

## Validation Strategy

- The parent-spec boundary tests (criteria 2-6, 8, 9, and 13 / shared kit
  plus preplan regression, with audit prose in 7 as a seam assertion).
- Compile the affected runtime modules. Do not use a full repo-root `check`
  as the acceptance bar for this subtask.

## Next Path

Parent next path: implement → audit as a `phase_prose` matrix row, not as
`implement_prose`.
