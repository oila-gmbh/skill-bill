# SKILL-212. Prose-centric plan phase I/O

## Context

Plan is the second agent-launched phase of a goal run. It must emit an
`executable_plan`: a closed JSON object with `projection_kind`, `mode`
(`direct` or `decompose`), non-empty `tasks` (lowercase-kebab `task_id`,
acyclic `depends_on`, `criterion_refs`, `target_paths_or_symbols`,
`test_obligations`, `constraints`), and `validation_strategy`. The producer
gate, the implement launch seam, and the goal-planning write/hydrate paths
all parse that object with `additionalProperties: false`. A near-miss
(nested wrapper, `T1` instead of `task-1`, empty `test_obligations`, prose
where an object is required) is rejected. The phase retries until a
schema-valid plan appears, or the run blocks.

SKILL-211 already made this call for preplan. Preplan `produced_outputs` is
`PhaseOutput` (`value`, optional `prompt`). Plan reads that prose. The digest
schema gate and the `regenerate_preplan` consumer edge are gone. That edge
is the proof: agents reason in prose, they can read shapes, and they do not
emit them reliably. Plan is the next shape gate that still deletes meaning.

The SKILL-211 wiring is still preplan-shaped. The schema `allOf`, the
handoff contract id `feature_task_runtime.preplan_prose`, the declaration
helper, and the projection decoder all name one producer. Copying that
bundle as `plan_prose` would force the same fork again for implement, then
audit. This skill generalizes that kit and points it at plan.

Implement still needs a plan. Audit still needs to know what was planned.
Those consumers today take a typed projection, not the agent words:

- Implement receives `feature_task_runtime.executable_plan` (`mode`, `tasks`,
  `validation_strategy`). The SKILL-150 completion gate then reads planned
  task ids from that *delivered* projection and refuses a `completed` receipt
  that does not close every id.
- Audit receives a derived `plan_commitment` (task/criterion/test obligations
  only), built from the same `executable_plan` via
  `FeatureTaskRuntimeExecutablePlan.toPlanCommitment`.
- Implement launch-seam rejection of a quarantined plan record re-enters
  plan under `regenerate_plan`.

This skill moves plan onto the shared prose kit. Implement still emits a
gated `implementation_receipt`. The next prose phase must not need a new
payload type, schema def, or handoff contract.

## Intended Outcome

One prose kit. Every prose producer emits the existing payload:

```kotlin
data class PhaseOutput(
  val value: String,
  val prompt: String? = null,
)
```

The outer envelope stays (`contract_version`, `phase_id`, `status`,
`summary`, …). Consumers read `value`, then `prompt` if present. They do
not schema-check recommended headings.

The kit is phase-neutral:

- One phase-output `$defs` entry for `produced_outputs` (`value` required,
  `prompt` optional, extra keys allowed). Preplan and plan `allOf` branches
  `$ref` that def. They do not duplicate the shape.
- One handoff contract, renamed from `feature_task_runtime.preplan_prose` to
  `feature_task_runtime.phase_prose`. Projection *names* stay
  `${producingPhaseId}_prose`. One declaration helper, parameterized by
  consumer and producer. One projection decoder. `directive` stays the
  optional mapped field from `prompt`.
- `producedProjectionKindFor(phaseId) == null` remains the switch that
  skips the planning-projection producer gate. Plan joins preplan on that
  path. Goal-planning write, hydrate, and checkpoint already share that
  gate, so they follow for free.
- A prose producer has no consumer regeneration edge. Blank `value` retries
  on the producing phase via the envelope schema. `regenerate_plan` goes
  the way `regenerate_preplan` already went.
- One shared `produced_outputs` JSON example for every prose phase. What
  belongs in `value` stays in the phase directive, not a second JSON shape.

Adopting a later phase is then: null the produced kind, `$ref` the shared
def on that `phase_id`, point consumers at `phaseProseDeclaration`, drop
that producer's regeneration edge, unwind consumers that still parse the
old gated shape. No `implement_prose` contract. No third copy of the
`allOf`.

Plan is the first client of that kit beyond preplan. Implement, audit-gap
implement, and audit consume plan through `phase_prose`. Recommended plan
headings remain in the plan prompt as guidance only.

## Acceptance Criteria

1. Existing `PhaseOutput` is the agent-authored shape of plan
   `produced_outputs`. No new payload type is introduced.
2. A completed plan whose `produced_outputs` is only a non-blank `value`
   advances to implement. The implement briefing contains that string.
3. Extra keys on plan `produced_outputs` (legacy `executable_plan` fields,
   runtime sidecar) are ignored, not rejected. The producer gate does not
   re-enter plan for missing `projection_kind`, a bad `task_id`, empty
   `test_obligations`, or a malformed `mode`.
4. Blank or missing `value` on `status: completed` is missing content: plan
   retries or blocks. Implement does not launch on an empty handoff.
5. When `prompt` is present, the implement briefing includes it. When
   absent, implement still launches from `value` alone.
6. `producedProjectionKindFor("plan")` is null. Implement no longer consumes
   `feature_task_runtime.executable_plan`. The `regenerate_plan` consumer
   edge from implement is gone. `RECORD_REJECTED` at implement does not
   bounce back to plan. Plan still retries its own envelope and
   blank-`value` failures.
7. Audit no longer consumes derived `plan_commitment`. The audit briefing
   (and the audit-gap re-entry of implement) carry plan `value` / `prompt`
   prose. `implementation_receipt` stays the gated producer claim audit
   already reads.
8. The SKILL-150 completion gate no longer reads planned task ids from a
   delivered `executable_plan`. A schema-valid `completed` receipt is not
   rejected for failing to close a gated plan task list. The gate still
   refuses `completed` with a non-empty `unresolved_items`, and audit-gap
   re-entry still closes carried repair items. `completed_task_ids` remains
   on the receipt as the implement claim.
9. A prose plan that still carries leftover `mode: decompose` (or other
   `executable_plan` keys) beside a non-blank `value` is a plan handoff, not
   a decomposition-package stop. Decomposition packages stay a separate
   shape under `produced_outputs.decomposition_package`. Goal-continuation
   prompts forbid emitting that package. They no longer talk about
   `produced_outputs.mode`.
10. Goal-planning `plan_payload` write, hydrate, and checkpoint paths accept
    prose `PhaseOutput`. They share the producer gate, so a null produced
    kind is enough: they must not still demand `executable_plan`. Runtime
    sidecars on plan still round-trip after a prose `value`.
11. Plan and implement phase prompts describe recommended prose, not a gated
    `executable_plan` JSON example. Implement is told to treat upstream as
    prose. Implement prompts must not claim `completed_task_ids` has to
    close a delivered plan task-id list. Preplan and plan share one
    `produced_outputs` JSON example.
12. In-flight `executable_plan` checkpoints loud-fail and regenerate in-band
    after the planning-projections contract bump that removes
    `executable_plan` and `plan_commitment`. In-flight
    `feature_task_runtime.preplan_prose` contract ids loud-fail and
    regenerate after the rename to `feature_task_runtime.phase_prose`.
13. The prose kit is shared, not forked:
    - Phase-output schema: one `$defs` shape. Preplan and plan both `$ref`
      it. There is no plan-only duplicate of the preplan `allOf` body.
    - One handoff contract `feature_task_runtime.phase_prose`. No
      `plan_prose`, `implement_prose`, or other per-phase sibling.
    - One declaration helper and one decoder. They do not switch on
      producer phase id beyond the source ref already on the declaration.
    - Preplan → plan still works after the rename: a completed preplan
      whose `produced_outputs` is only a non-blank `value` still advances
      to plan with that string in the briefing.
    - Prose-handoff tests are one helper (or parameterized rows) covering
      preplan → plan and plan → implement. They are not a cloned second
      suite.
14. Automated tests cover criteria 2-6, 8, 9, and 13 (shared kit plus
    preplan regression). Plan producer-gate tests that only asserted
    `executable_plan` schema rejection are deleted or rewritten. The
    goal-planning test that rejects empty `test_obligations` on plan is
    deleted or rewritten.

## Constraints

- Keep the outer envelope. Do not treat the agent stdout as
  `PhaseOutput`.
- Reuse `PhaseOutput`. Do not rename it, and do not name anything new
  `FeatureTaskRuntimePhaseOutput`.
- Do not mint a per-phase prose payload, schema def, contract id,
  declaration helper, or decoder. The next prose producer must be a
  matrix change against this kit.
- Do not schema-check recommended plan fields (`tasks`, kebab `task_id`,
  `mode`, `validation_strategy`, `projection_kind`).
- Do not parse task ids or criterion refs out of plan prose to keep the
  completion gate or `plan_commitment` alive.
- Preserve loud-fail for envelope failures, blank `value`, agent process
  failure, and missing manifests or contract-version drift.
- Do not require a second LLM format-repair pass.
- The implement `implementation_receipt` producer gate stays.
- A real `decomposition_package` without `projection_kind` may still stop
  a standalone run at planning. Detect it only from that nested object,
  never from a leftover top-level `mode` on a `value` payload.
- Preplan behaviour is unchanged: `value` / optional `prompt`, extra keys
  ignored, blank `value` retries. Only the names and sharing of the kit
  change.

## Non-Goals

- Moving implement, audit, validate, or commit onto `PhaseOutput` in this
  skill. The kit must make that a later matrix change. This skill does not
  perform it.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output` field names.
- Changing review (already on `AgentPhaseOutput`).
- Restoring structured task-id injection from plan into implement.
- Removing the standalone decomposition-package stop or changing
  spec-prep decomposition.
- Remote telemetry breakdowns for `executable_plan` failures.

## Decomposition Rationale

One subtask. Generalizing the prose kit and pointing it at plan are the
same handoff. Schema def, contract rename, producer gate, implement
briefing, audit's plan-derived view, completion-gate obligation source,
goal-planning plan payload, `regenerate_plan`, decompose detection, and
prompts cannot land as "add plan_prose" first. That is the fork this skill
exists to prevent.

## Next Path

The next shape-gated edge, likely implement → audit, should be a matrix
change against `phase_prose`: null the produced kind, `$ref` the shared
def, retarget consumers, drop that producer's regeneration edge, unwind
`implementation_receipt` only when that skill lands. It must not add
`implement_prose`.
