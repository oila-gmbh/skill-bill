# SKILL-213 subtask 1 — Replace implementation_receipt with phase prose

## Scope

Unify preplan, plan, and implement on one prose I/O model: same `value`/
optional `prompt` shell; stuff the former gated JSON (digest, plan, receipt)
inside `value` as structured prose; runtime forwards verbatim; next agent
interprets. Fix SKILL-211/212 prompt drift. Land implement → audit.

In scope:

- Add implement to the phase-output `allOf` branch that `$ref`s
  `phaseProseProducedOutputs`. Preplan, plan, and implement share one def.
- Remove `implementation_receipt` from the planning-projections `oneOf` and
  bump that contract version.
- `producedProjectionKindFor(implement)` returns null. Audit and
  `auditRemediationProjections` consume `phaseProseDeclaration` from implement,
  not `implementationReceiptDeclaration`.
- Remove the audit → implement `regenerate_implement` consumer edge and the
  `PHASE_IMPLEMENT` entry in `REGENERATION_LOOP_ID_BY_PRODUCER`.
- Remove `mutatingReconciliationGateReason` and receipt-shaped completion gates
  for implement. Same content gate as preplan and plan: blank `value` only.
- Remove receipt-shaped closure (`completed_task_ids`, `repair_item_results`,
  `unresolved_items`, plan task ids, audit-gap repair ids) on `completed`.
- Implementation-attempt schema bump: record `value`, optional `prompt`, and
  attempt identity per segment; drop structured receipt fields from required
  storage. Continuation prompts rebuild from stuffed `value` history.
- `finalizationProjectionContext` and friends: derive changed paths only from
  repository checkpoint working-tree inventory, not from receipt fields inside
  or beside `value`.
- Replace `FeatureTaskRuntimePhaseProjectionShapes` and phase directives for
  preplan, plan, and implement: same `value`/`prompt` shell; show former digest /
  plan / receipt JSON as the inner object to stuff into `value`.
- Shared prose-handoff tests covering preplan → plan, plan → implement, and
  implement → audit with one helper.
- Delete `implementation_receipt`, mutating-reconciliation, and receipt-shaped
  completion rejection siblings that no longer name a real bug.

## Acceptance Criteria

1. Existing `PhaseOutput` is the agent-authored shape of implement
   `produced_outputs`. There is no new payload type and no `implement_prose`
   contract.
2. A completed implement whose `produced_outputs` is only `{ "value": "…" }`
   with the former receipt JSON stuffed inside advances to audit. The audit
   briefing contains that string.
3. Legacy receipt keys beside `value`, absent `reconciled_state`, and malformed
   inner receipt in `value` still complete. The producer gate does not re-enter
   implement for receipt-schema violations.
4. Blank or missing `value` on `status: completed` retries or blocks implement.
5. Optional `prompt` appears in the audit briefing when present and is omitted
   cleanly when absent.
6. `producedProjectionKindFor("implement")` is null. Audit receives raw
   implement `value` in the briefing and does not parse
   `feature_task_runtime.implementation_receipt`. The `regenerate_implement`
   consumer edge from audit is gone.
7. Audit-gap implement receives plan prose, audit repair request, prior-gap
   memory, and prior implement `value` through `phase_prose`, not through
   `implementationReceiptDeclaration`.
8. Implement has no completion gate beyond blank `value` that preplan and plan
   do not already share.
9. Validate, build, and write-history still launch with changed-path inventory
   from the repository checkpoint, not from receipt fields inside or beside
   `value`.
10. Implementation-attempt persistence and continuation prompts use stuffed
    `value` segments; they do not enumerate `openObligationIds` from parsed
    receipt fields.
11. Preplan, plan, and implement share the same `value`/`prompt` shell. Each
    shows its former gated JSON as content stuffed into `value` (not free-form
    NL, not sibling keys). Plan reads preplan `value`; implement reads plan
    `value`; audit reads implement `value` — all verbatim, all interpreted by
    the agent.
12. Malformed or partial JSON inside non-blank `value` still advances
    preplan → plan, plan → implement, and implement → audit.
13. `status: completed` with receipt stuffed into `value`, or with legacy keys
    beside `value`, is not rejected by `mutating-reconciliation` or the
    removed producer gate.
14. Preplan, plan, and implement `$ref` one `$defs` produced-outputs shape.
    Audit consumes both plan and implement through
    `feature_task_runtime.phase_prose`.

## Non-Goals

- Rewiring audit `gaps`, validate, review, or later phases onto `PhaseOutput`.
- Renaming `AgentPhaseOutput.output` or `PhaseOutput`.
- Runtime `jsonDecode` or field extraction of `value` at any prose handoff
  seam (preplan → plan, plan → implement, implement → audit).
- Changing audit-gap pause / no-progress policy (SKILL-205).
- Telemetry proxy stats for this edge.

## Dependency Notes

- Depends on: none (only subtask). SKILL-212 already shipped `phase_prose` and
  the plan/preplan wiring this change extends.
- Unblocks: later skills that evaluate other edges for prose handoff without a
  new contract.

## Validation Strategy

- The parent-spec boundary tests (criteria 2–6, 8–12, and 14). Include
  stuffed-JSON-in-`value` advance with verbatim briefing text on all three
  edges, legacy-keys-beside-`value` tolerance, malformed-inner-JSON tolerance,
  and prompt examples that show former JSON inside `value` rather than dense
  NL.
- Compile the affected runtime modules. Do not use a full repo-root `check` as
  the acceptance bar for this subtask.

## Next Path

Parent next path: evaluate the next agent-authoritative edge for the same kit
only where prose is the right carrier; do not fork `phase_prose` per consumer.
