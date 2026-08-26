# SKILL-211 — Prose-centric preplan phase I/O

## Context

Preplan is the first agent-launched phase of a goal run. It must emit a
`preplanning_digest`: a closed JSON object with `projection_kind`, non-empty
`affected_boundaries` / `risks` / `validation_strategy`, and a `rollout` object
that is never a string. The producer gate and the plan launch seam both parse
that digest with `additionalProperties: false`. A near-miss (nested wrapper,
prose `rollout`, missing discriminator, payload that is not a JSON object) is
rejected. The phase retries until a schema-valid digest appears, or the run
blocks.

That design fights how agents write. They reason in prose. They can *read*
shapes; they do not emit them reliably. In this install, fifteen recent preplan
attempts failed with `Goal planning 'preplan' payload is not a JSON object`
(SKILL-208 and SKILL-13). Those were digest-generation failures, not missing
planning thought.

SKILL-207 already made this call for review: the authoritative result is a
string; shape in the prompt is guidance, not a filter that deletes meaning.
This skill does the same for the preplan → plan edge only.

## Intended Outcome

Preplan’s `produced_outputs` is a two-field prose payload:

```kotlin
data class PhaseOutput(
  val value: String,
  val prompt: String? = null,
)
```

The outer phase envelope stays (`contract_version`, `phase_id`, `status`,
`summary`, …). Plan reads `value`, then `prompt` if present, as prose. It does
not schema-check digest fields. Recommended digest headings remain in the
preplan prompt as guidance only.

`PhaseOutput` lives next to SKILL-207’s `AgentPhaseOutput` in
`skillbill.agent.model`. It is not `FeatureTaskRuntimePhaseOutput` (that name
already means the persisted `{phaseId, attempt, payload}` record). `value` is
this phase’s prose (same role as `AgentPhaseOutput.output`). `prompt` is
optional instructions for the *next* phase, not SKILL-207’s inbound
`requestedAction`.

Later phases may reuse `PhaseOutput`. This skill wires preplan → plan only.
Plan still emits a gated `executable_plan`.

## Acceptance Criteria

1. A `PhaseOutput` type exists with `value: String` and `prompt: String?` and
   is the agent-authored shape of preplan `produced_outputs`.
2. A completed preplan whose `produced_outputs` is only a non-blank `value`
   advances to plan; the plan briefing contains that string.
3. Extra keys on preplan `produced_outputs` (legacy digest fields, runtime
   sidecar) are ignored, not rejected; the producer gate does not re-enter
   preplan for missing `projection_kind` or a malformed `rollout`.
4. Blank or missing `value` on `status: completed` is missing content: preplan
   retries or blocks; plan does not launch on an empty handoff.
5. When `prompt` is present, the plan briefing includes it; when absent, plan
   still launches from `value` alone.
6. `producedProjectionKindFor("preplan")` is null. Plan no longer consumes
   `feature_task_runtime.preplanning_digest`. The `regenerate_preplan` consumer
   edge from plan is gone. Preplan still retries its own envelope and
   blank-`value` failures.
7. Goal-planning `_goal_planning_shared_context` remains a runtime-owned extra
   key the agent never emits, and still round-trips after a prose `value`.
8. Stale shared-preplan refresh no longer keys off `selected_boundary_headings`.
   It compares `value` (and `prompt`) hashes. Plan still receives the boundary
   catalog from the planning packet and does not get auto-resolved bodies from
   a gated heading list.
9. Preplan and plan phase prompts describe recommended prose, not a gated
   digest JSON example. Plan is told to treat upstream as prose.
10. In-flight `preplanning_digest` checkpoints loud-fail and regenerate in-band
    after the planning-projections contract bump.
11. Automated tests cover criteria 2–5 and 7. Preplan producer-gate tests that
    only asserted digest-schema rejection are deleted or rewritten.

## Constraints

- Keep the outer envelope. Do not treat the agent’s entire stdout as
  `PhaseOutput`.
- Do not name the new type `FeatureTaskRuntimePhaseOutput`.
- Do not schema-check recommended digest fields (`affected_boundaries`,
  `rollout` object shape, `selected_boundary_headings`, `projection_kind`).
- Preserve loud-fail for envelope failures, blank `value`, agent process
  failure, and missing manifests or contract-version drift.
- Do not require a second LLM format-repair pass.
- Plan’s `executable_plan` producer gate stays.

## Non-Goals

- Moving plan, implement, audit, validate, or commit onto `PhaseOutput`.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output` field names.
- Changing review (already on `AgentPhaseOutput`).
- Remote telemetry breakdowns for digest failures.
- Restoring structured heading-body injection from preplan into plan.

## Decomposition Rationale

One subtask. Schema, producer gate, plan briefing, goal-sweep sidecar/refresh,
and prompts are one handoff change. Splitting “add the type” from “read the
type” would leave a commit that nothing consumes.

## Next Path

Adopt `PhaseOutput` on the next feature-task edge that still loses meaning to
a shape gate, likely plan → implement, without weakening `executable_plan`
until that skill lands.
