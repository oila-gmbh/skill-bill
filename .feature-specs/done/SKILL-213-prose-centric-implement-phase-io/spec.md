# SKILL-213 — Prose-centric planning phase I/O (implement → audit, preplan/plan alignment)

## Philosophy

The refactor does **not** change what agents are asked to produce or what
downstream phases need to know. It changes how the **runtime** treats that
content.

Applies identically to **preplan, plan, and implement**:

- **Generation stays familiar.** Authors still write the same JSON-shaped
  material they wrote before (`preplanning_digest`, `executable_plan`,
  `implementation_receipt` field lists). Prompts still teach those shapes.
- **Wire shape is `value` / optional `prompt`.** That familiar JSON lives
  **inside `value`** (typically as a JSON string), not as schema-gated sibling
  keys on `produced_outputs`.
- **Runtime does not treat it as structured data.** No producer gate, no
  `jsonDecode`, no field extraction, no `additionalProperties: false` filter
  that deletes near-misses. Non-blank `value` is enough to advance.
- **Downstream reads structured prose.** The next phase receives the raw
  `value` string and interprets it: it may look like JSON, it may be invalid
  or partial JSON, and that must not block the handoff. The AI extracts what
  it needs; Kotlin forwards the string verbatim.

**Not** free-form natural-language prose in `value`. **Structured prose**:
JSON-shaped text the agent authored, interpreted by the next agent — same
content as before, different seam.

SKILL-211 and SKILL-212 shipped the `value`/`prompt` wire shape and removed
producer gates, but prompts drifted to “dense planning prose” instead of
**stuffing the former digest/plan JSON into `value`**. That drift is out of
intent and is fixed in this skill alongside implement → audit.

## Context

Implement is the third agent-launched phase of a goal run. It must emit an
`implementation_receipt`: a closed JSON object with `projection_kind`,
`completed_task_ids`, `changed_paths`, `tests_executed`,
`reconciliation_evidence`, and related fields. The producer gate, the audit
launch seam, validate/build `validation_request` derivation, write-history
`boundary_candidates`, semantic continuation, and the durable
implementation-attempt store all parse that object with
`additionalProperties: false`. A near-miss (nested wrapper, `Task-01` instead
of `task-1`, empty `tests_executed`, prose where an array is required) is
rejected. The phase retries until a schema-valid receipt appears, or the run
blocks.

SKILL-211 and SKILL-212 already moved preplan and plan onto the
`value`/`prompt` wire and dropped the digest / `executable_plan` producer
gates. That part matches this philosophy. Their prompts drifted: they teach
free-form “dense planning prose” instead of stuffing the former digest and
`executable_plan` JSON into `value`. This skill fixes that drift and applies
the same stuffed-JSON model to implement → audit. Implement is still the
shape gate that deletes meaning.

Audit still needs to know what was implemented. Validate, build, and
write-history still need changed-path inventory, but those consumers already
refresh repository checkpoints and derive paths from the working tree; they
must not keep a gated receipt alive only as a path carrier. Semantic
continuation and the implementation-attempt store still carry structured
receipt fields from every segment; those paths must slim down with the
producer handoff, not stay as a shadow gate on the same output.

This skill puts implement on the same `phase_prose` kit and aligns preplan
and plan prompts with it: JSON envelope outside, former gated object stuffed
inside `value`, optional `prompt`, extra keys ignored, downstream interprets
`value` as structured prose. Audit still emits a gated `gaps` / `verdict`
envelope. The next prose phase must not need a new payload type, schema def,
or handoff contract.

## Intended Outcome

One prose kit for preplan, plan, and implement. Each emits:

```kotlin
data class PhaseOutput(
  val value: String,
  val prompt: String? = null,
)
```

**Stuff-in-`value` migration (all three prose producers):**

| Phase | Former gated object | Goes inside `value` |
|-------|---------------------|---------------------|
| preplan | `preplanning_digest` | same field list |
| plan | `executable_plan` | same field list |
| implement | `implementation_receipt` | same field list |

Prompts show the **inner object** authors already know, wrapped in the
`value`/`prompt` shell. Plan reads preplan `value` verbatim and interprets it.
Implement reads plan `value` verbatim and interprets it. Audit reads implement
`value` verbatim and interprets it. No Kotlin parsing at any of those seams.

**Align preplan and plan with this intent.** Replace “dense planning prose”
prompt wording and examples with stuffed-former-JSON guidance. Any test or
directive that teaches free-form NL in `value` instead of JSON-shaped
structured prose is corrected.

The outer envelope stays (`contract_version`, `phase_id`, `status`,
`summary`, …).

**Audit consumer.** Audit still receives the same receipt content it relied on
before — `completed_task_ids`, `changed_paths`, `tests_executed`, deviations,
reconciliation claims, and the rest — but as the raw `value` string (plus
optional `prompt`), not as a runtime-parsed `implementation_receipt`
projection. The Kotlin seam forwards `value` verbatim into the audit briefing.
It does not `jsonDecode` `value`, validate field shapes, or extract typed
fields for the agent. The audit phase reads that string as structured prose:
it may look like JSON, but the agent interprets it and pulls out what it needs
the same way plan interprets preplan `value` or audit already interprets plan
`value`. Near-miss or partial JSON in `value` must not block audit launch.

The kit gains one matrix row:

- Implement `allOf` on the phase-output schema `$ref`s the shared
  `phaseProseProducedOutputs` def. Preplan, plan, and implement share one
  `$defs` shape.
- Audit adds `phaseProseDeclaration(PHASE_AUDIT, PHASE_IMPLEMENT)` beside the
  existing plan-prose declaration. Projection name stays
  `${producingPhaseId}_prose`. Contract id stays
  `feature_task_runtime.phase_prose`.
- `producedProjectionKindFor("implement")` is null. The planning-projections
  schema drops `implementation_receipt`. Goal-planning write, hydrate, and
  checkpoint follow the null produced kind.
- A prose producer has no consumer regeneration edge. Blank `value` retries on
  the producing phase via the envelope schema. `regenerate_implement` goes the
  way `regenerate_plan` already went.
- One shared `produced_outputs` shell for preplan, plan, and implement —
  `value` plus optional `prompt`. Each directive shows its former gated object
  as the **content of `value`** (not as sibling keys on `produced_outputs`,
  not as free-form natural language).

Downstream derivation changes with the receipt:

- Audit briefings and audit-gap implement re-entry carry implement `value`
  verbatim (the stuffed receipt string), not a parsed
  `implement_implementation_receipt` projection.
- The `mutating-reconciliation` gate (`mutatingReconciliationGateReason`) is
  removed for implement. Idempotency guidance stays in the mutating-phase
  directive; the runtime does not schema-check `reconciled_state`.
- Validate/build `validation_request` and write-history `boundary_candidates`
  keep using repository checkpoint working-tree paths. They do not read
  `changed_paths` from a delivered receipt.
- The durable implementation-attempt store records bounded prose per segment
  (`value`, optional `prompt`, attempt identity) instead of a structured
  receipt clone. Continuation prompts rebuild from that prose history, not
  from `completed_task_ids` or `openObligationIds` derived from a gated plan
  or repair list.

Adopting a later phase is then: null the produced kind, `$ref` the shared
def on that `phase_id`, retarget consumers, drop that producer's regeneration
edge, unwind the old gated shape only when that skill lands. No
`audit_prose` contract. No fourth copy of the `allOf`.

## Acceptance Criteria

1. Existing `PhaseOutput` is the agent-authored shape of implement
   `produced_outputs`. No new payload type is introduced.
2. A completed implement whose `produced_outputs` is only a non-blank `value`
   (with the former receipt JSON stuffed inside that string) advances to
   audit. The audit briefing contains that string unchanged.
3. Extra keys on implement `produced_outputs` (legacy receipt keys emitted
   beside `value`, `reconciled_state`, `reconciliation_evidence`, runtime
   sidecar) are ignored, not rejected. The producer gate does not re-enter
   implement for missing `projection_kind`, a bad `completed_task_ids` entry,
   empty `tests_executed`, a malformed inner receipt in `value`, or absent
   `reconciled_state` inside or beside `value`.
4. Blank or missing `value` on `status: completed` is missing content:
   implement retries or blocks. Audit does not launch on an empty handoff.
5. When `prompt` is present, the audit briefing includes it. When absent,
   audit still launches from `value` alone.
6. `producedProjectionKindFor("implement")` is null. Audit no longer consumes
   or parses `feature_task_runtime.implementation_receipt`. The briefing
   carries raw implement `value`; the audit agent interprets it. The
   `regenerate_implement` consumer edge from audit is gone. `RECORD_REJECTED`
   at audit does not bounce back to implement for receipt-schema drift.
   Implement still retries its own envelope and blank-`value` failures.
7. Audit-gap implement re-entry receives plan `value`, audit repair request,
   prior-gap memory, and the latest implement `value` from the prior segment
   through the same `phase_prose` helper — not a bounded receipt projection.
   Those `value` strings are forwarded verbatim.
8. Implement has no completion gate beyond blank `value` that preplan and plan
   do not already share. Receipt-shaped closure (`completed_task_ids`,
   `repair_item_results`, `unresolved_items`, plan task ids, audit-gap repair
   ids) is not enforced on `status: completed`.
9. Validate, build, and write-history projections still launch. Changed-path
   inventory comes from the refreshed repository checkpoint, not from a
   delivered `implementation_receipt`.
10. The durable implementation-attempt store records prose segments (`value`,
    optional `prompt`, attempt identity) instead of structured receipt fields.
    Continuation prompts carry prior implement `value`; they do not enumerate
    `openObligationIds` from a gated plan or repair list.
11. Preplan, plan, and implement share the same `produced_outputs` shell
    (`value`, optional `prompt`). Each directive shows its **former gated JSON
    object as the content to stuff into `value`**, not as sibling keys and not
    as free-form natural language. Plan, implement, and audit prompts tell the
    agent to read upstream `value` as structured prose and interpret it; the
    runtime forwards it verbatim.
12. Malformed, partial, or non-JSON inner content in non-blank `value` still
    advances the handoff at every prose edge (preplan → plan, plan → implement,
    implement → audit). Only blank `value` blocks.
13. `status: completed` with only `value` (receipt stuffed inside), with legacy
    receipt keys beside `value`, or without `reconciled_state` anywhere is not
    rejected by `mutating-reconciliation` or the removed
    `implementation_receipt` producer gate.
14. In-flight `implementation_receipt` checkpoints loud-fail and regenerate
    in-band after the planning-projections contract bump that removes
    `implementation_receipt`.
15. The prose kit stays shared, not forked:
    - Phase-output schema: one `$defs` shape. Preplan, plan, and implement
      all `$ref` it.
    - One handoff contract `feature_task_runtime.phase_prose`. No
      `implement_prose` or other per-phase sibling.
    - Plan → implement and implement → audit prose-handoff tests share one
      helper (or parameterized rows). They are not cloned suites.
    - Preplan and plan prompts/examples match implement: stuffed former JSON in
      `value`, not “dense planning prose” drift from SKILL-211/212.
16. Automated tests cover criteria 2–6, 8–12, and 15. Handoff tests use
    one helper for preplan → plan, plan → implement, and implement → audit with
    JSON-shaped strings in `value`. Prompt tests no longer require inner shapes
    to satisfy planning-projection schema gates.

## Constraints

- Keep the outer envelope. Do not treat the agent stdout as `PhaseOutput`.
- Reuse `PhaseOutput`. Do not rename it, and do not name anything new
  `FeatureTaskRuntimePhaseOutput`.
- Do not mint a per-phase prose payload, schema def, contract id,
  declaration helper, or decoder.
- Do not schema-check the object stuffed inside `value` on preplan, plan, or
  implement, nor former gated fields emitted as legacy sibling keys on
  `produced_outputs`.
- Do not parse `value` into typed digest, plan, or receipt fields at any
  Kotlin handoff seam. Forward the raw string; the next agent interprets it.
- Preserve loud-fail for envelope failures, blank `value`, agent process
  failure, and missing manifests or contract-version drift.
- Do not require a second LLM format-repair pass.
- The audit `gaps` / `verdict` producer gate stays.
- Implement obeys the same prose I/O rules as preplan and plan: non-blank
  `value` is the only content gate on `status: completed`; inner content is
  JSON-shaped structured prose, not schema-validated data.
- Preplan and plan keep the same `value`/`prompt` wire and ungated producer
  path. Their prompts and examples change only to stuff the former JSON into
  `value` instead of teaching free-form prose.

## Non-Goals

- Moving audit, validate, verify_findings, review, build, write_history, or
  commit onto `PhaseOutput` in this skill.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output` field names.
- Changing review (already on `AgentPhaseOutput`).
- Restoring structured task-id or repair-item injection from implement into
  audit.
- Removing the audit `gaps` compact report or changing audit-gap convergence
  policy beyond dropping receipt-shaped closure on implement.
- Remote telemetry breakdowns for `implementation_receipt` failures.

## Decomposition Rationale

One subtask. Pointing implement at `phase_prose`, aligning preplan/plan prompts
with stuffed-JSON-in-`value`, and retargeting audit, regeneration, continuation,
attempt persistence, downstream path derivation, and tests is one handoff. Splitting “add implement to the
schema def” from “read implement prose at audit” would leave a commit that
nothing consumes.

## Next Path

The next shape-gated edge, likely audit → validate or implement_fix →
validate, should be evaluated against the same prose kit only where the agent
words are authoritative. Do not add `audit_prose` unless audit itself becomes
a prose producer in a later skill.
