# SKILL-214 — Prose-centric audit phase I/O (audit → review, audit → implement gap re-entry)

## Philosophy

Same refactor as SKILL-211, SKILL-212, and SKILL-213, applied to the fourth
phase. It does not change what the audit agent is asked to produce or what
downstream phases need to know. It changes how the **runtime** treats that
content.

- **Generation stays familiar.** The audit agent still writes the same
  `gaps` / `non_blocking_findings` material it writes today: one entry per
  unmet acceptance criterion, `AC-###` ref plus a dense note carrying a
  complete implement-ready fix plan. Prompts still teach that shape.
- **Wire shape is `value` / optional `prompt`.** That familiar JSON lives
  **inside `value`**, not as schema-gated sibling keys on `produced_outputs`.
- **Runtime does not treat it as structured data.** No gap-array parse, no
  severity classification, no criterion-ref extraction, no
  satisfied-versus-gaps contradiction check. Non-blank `value` is enough.
- **Downstream reads structured prose.** The implement gap re-entry receives
  the raw `value` string and interprets it. Near-miss or partial JSON must not
  block the handoff.

**One deliberate exception, and it is the whole line this skill draws.** The
envelope-level `verdict` (`satisfied` | `gaps_found`) stays required and
enum-gated. It is not agent prose the next agent interprets — it is the
runtime's own routing decision: it gates entry into `review` and it fires the
`audit_gap` backward edge to `implement`. SKILL-213 said the prose kit applies
"only where the agent words are authoritative"; for audit, the words are
authoritative and the verdict is not. So the verdict stays a machine signal on
the envelope, and everything inside `produced_outputs` becomes prose.

## Context

Audit is the fourth agent-launched phase. Today its `produced_outputs` is a
closed, heavily-policed object:

- The phase-output schema requires `gaps`, constrains each entry to
  `unmetCriterion` (`criterion` matching `^AC-[0-9]{3}$`, `note` 1–1024 chars
  with no newlines or backticks), constrains `non_blocking_findings` severities
  to `minor`/`nit`, and blacklists six legacy keys (`unmet_criteria`,
  `audit_repair_plan`, `carried_gap_dispositions`, `blast_radius_inspection`,
  `prior_gap_dispositions`, `failing_criteria`) through a `not: anyOf`.
- Two further schema branches enforce verdict-to-array coherence: `satisfied`
  requires `gaps` to be empty, `gaps_found` requires it non-empty.
- `FeatureTaskRuntimeOutputVerification` re-implements the same checks in
  Kotlin and adds more: `auditGapPayloadError` rejects the `failing_criteria`
  alias, rejects a non-empty legacy `unmet_criteria`, and rejects a
  `gaps_found` round where any entry fails to parse as a blocking gap.
  `auditVerdictFrom` parses the array to derive the verdict;
  `canonicalAuditCriterionRefs` regex-scrapes `AC-\d+` out of the notes.
- `FeatureTaskRuntimeVerificationGateReasons.auditVerificationSignal` layers a
  third pass over the same output. It calls `auditGapPayloadError` again, then
  checks for a missing or off-vocabulary verdict — but those two checks sit
  behind `if (hasGapsArray || …) return null`, so they only fire when
  `produced_outputs` carries no gaps array, and the schema already requires
  one. They are unreachable behind the schema gate today: a third pass that
  adds nothing the first two do not.
- `FeatureTaskRuntimeStrictWireMapping.kt` holds a fourth description of audit
  gap shape (`AUDIT_REPAIR_PLAN_KEYS`, `AUDIT_REPAIR_GAP_KEYS`,
  `AUDIT_REPAIR_ITEM_KEYS`, `AUDIT_REPAIR_STATE_KEYS`, and the rest). Every
  symbol in that file is declared and referenced nowhere — it is the residue of
  a removed audit-repair-plan model.

A near-miss (`AC-7` instead of `AC-007`, a backtick in a note, a nested
wrapper, a legacy key emitted alongside the canonical one) is rejected and the
phase retries, or the run blocks. This is the same shape gate SKILL-211/212/213
removed from preplan, plan, and implement, with more surface area than any of
them.

The gap bodies feed exactly one consumer that needs them: the implement
gap re-entry, which is another agent. The runtime's own derivations from the
array are thinner than they look:

- `audit_repair_request.unmet_criteria` re-delivers scraped criterion refs to
  that same implement re-entry, which is already about to read the notes.
- `audit_clearance.clearance_status` / `verdict` for review, validate, and
  build is derived from `gaps` emptiness when the envelope already carries the
  authoritative verdict.
- Prior-gap memory carries `prior_unmet_criteria` (the same notes again),
  `sticky_ids` (criterion refs unmet in two consecutive audits), and
  `last_implement_claims` — which SKILL-213 already reduced to a dead
  `emptyList()` stub that still ships a declared projection field.

This skill puts audit on the same `phase_prose` kit: JSON envelope outside,
gaps object stuffed inside `value`, optional `prompt`, extra keys ignored,
downstream interprets `value` as structured prose. The verdict stays gated.

## Intended Outcome

Four phases — preplan, plan, implement, audit — share one prose kit. Audit
emits:

```kotlin
data class PhaseOutput(
  val value: String,
  val prompt: String? = null,
)
```

**Stuff-in-`value` migration:**

| Phase | Former gated object | Goes inside `value` |
|-------|---------------------|---------------------|
| preplan | `preplanning_digest` | same field list |
| plan | `executable_plan` | same field list |
| implement | `implementation_receipt` | same field list |
| audit | `gaps` + `non_blocking_findings` | same field list |

The audit prompt shows the **inner object** the agent already knows —
`AC-###` refs, dense notes with a complete fix plan, minor/nit findings parked
in `non_blocking_findings` — wrapped in the `value`/`prompt` shell. The
implement gap re-entry reads audit `value` verbatim and interprets it. No
Kotlin parsing at that seam.

**The envelope keeps its verdict.** `phase_id: audit` still requires a
top-level `verdict` of `satisfied` or `gaps_found`. That is the only shape
constraint audit keeps, and the only one whose value the runtime reads. Audit
is therefore the first prose producer with a gated envelope field; the schema
expresses that as the existing verdict branch plus the shared
`phaseProseProducedOutputs` `$ref`, not as a new def or a fourth `allOf`
variant of the prose shell.

**Exactly one place knows audit's shape.** That place is the `phase_id: audit`
schema branch: `required: [verdict]` plus the two-value enum, and nothing else.
The Kotlin gate does not re-check it.
`FeatureTaskRuntimeVerificationGateReasons.auditVerificationSignal` is deleted
outright rather than reduced to its verdict cases — with gap parsing gone those
cases are exactly what the schema enum already rejects, and keeping them would
reproduce in miniature the duplication this skill exists to remove. The dead
`FeatureTaskRuntimeStrictWireMapping.kt` goes with it.

**The verdict is the sole verdict source.** Every derivation that inferred
`satisfied` / `gaps_found` from array emptiness reads the envelope instead:

- `FeatureTaskRuntimeOutputVerification.verdictFor(PHASE_AUDIT, …)` maps the
  wire verdict to the audit vocabulary and returns it. It does not re-reject an
  off-vocabulary value (the schema already did), and it does not silently fall
  back to `ADVANCE` for audit. There is no `auditVerdictFrom`, no
  `auditCriterionGapFromEntry`, no gap-derived override.
- `audit_clearance` for review, validate, and build carries that same wire
  verdict. It does not inspect `produced_outputs`.
- The `review` entry gate and the `audit_gap` backward edge are unchanged: they
  already key on the verdict.
- A completed audit with a verdict outside the vocabulary, or with no verdict,
  still fails loudly. That is an envelope failure, the same class as blank
  `value`.

**Gap-shape policing is gone.** `auditGapPayloadError`,
`auditVerificationSignal` in full, the alias and legacy-key rejections, the
satisfied/gaps_found array-coherence branches, the `unmetCriterion` `$defs`,
the `not: anyOf` legacy blacklist, and the dead
`FeatureTaskRuntimeStrictWireMapping.kt` are all removed. Legacy keys emitted
beside `value` are ignored, not rejected.

**Audit-gap re-entry carries prose.** `auditRemediationProjections()` delivers
plan `value`, implement `value`, and audit `value` through the same
`phaseProseDeclaration` helper, plus prior-gap memory. The
`audit_repair_request` projection's `unmet_criteria` field and the
`unmetCriterionRefs` input that fed it are gone; the repository-checkpoint
refresh that projection performed is preserved on the audit prose edge, so the
re-entry still runs against a refreshed checkpoint.

**Prior-gap memory becomes prose carry-forward.** It stops being a parsed
projection of criterion refs and becomes: the round number plus the earlier
rounds' audit `value` strings, bounded and forwarded verbatim. Specifically:

- `prior_unmet_criteria`, `last_implement_claims`, and `sticky_ids` are
  removed, along with `canonicalAuditCriterionRefs`, the two-audit sticky
  intersection, and the dead `lastImplementClaims()` stub.
- The memory carries `round` and a bounded list of prior audit `value` strings
  from rounds before the current one. The current round's audit `value` already
  arrives through the prose declaration; memory supplies the history that
  declaration does not.
- Existing UTF-8 and list bounds still apply, and over-budget truncation still
  emits an observability record.
- Recurrence detection moves to the agent. The audit and implement directives
  tell the round to compare the current gaps against the prior audit `value`
  strings and to re-justify any criterion it repeats. The runtime no longer
  computes sticky ids, so no prompt claims a runtime-derived sticky list.

Adopting a later phase stays the same recipe: null the produced kind if any,
`$ref` the shared def on that `phase_id`, keep only the envelope fields the
runtime routes on, retarget consumers to raw `value`, unwind the old gated
shape. No `audit_prose` contract. No fifth copy of the `allOf`.

## Acceptance Criteria

1. Existing `PhaseOutput` is the agent-authored shape of audit
   `produced_outputs`. No new payload type, schema def, contract id,
   declaration helper, or decoder is introduced for audit.
2. A completed audit whose `produced_outputs` is only a non-blank `value`
   (with the former gaps object stuffed inside that string) and whose envelope
   carries a valid verdict advances. The implement gap re-entry briefing
   contains that string unchanged.
3. Extra keys on audit `produced_outputs` are ignored, not rejected. A round
   carrying `gaps`, `unmet_criteria`, `failing_criteria`, `audit_repair_plan`,
   `carried_gap_dispositions`, `blast_radius_inspection`, or
   `prior_gap_dispositions` beside `value` completes on the strength of
   `value` and the verdict alone.
4. Malformed inner content in a non-blank `value` still advances: a criterion
   ref of `AC-7`, a note containing a backtick or a newline, a note over 1024
   characters, a nested wrapper object, a `minor` severity inside the gaps
   list, or text that is not JSON at all. Only blank or missing `value` on
   `status: completed` blocks; audit retries or blocks and the gap re-entry
   does not launch on an empty handoff.
5. When `prompt` is present, the implement gap re-entry briefing includes it.
   When absent, the re-entry still launches from `value` alone.
6. A completed audit with a top-level `verdict` outside
   `{satisfied, gaps_found}`, or with no verdict at all, fails loudly and
   re-enters audit. This is the only shape gate audit keeps, and the
   `phase_id: audit` schema branch is the only place that enforces it: no
   Kotlin gate re-checks the verdict. `auditVerificationSignal` and
   `FeatureTaskRuntimeStrictWireMapping.kt` no longer exist, and
   `outputVerificationGateReason` has no audit branch.
7. `verdict: gaps_found` with an empty or absent gaps array inside `value`, and
   `verdict: satisfied` with gap-looking text inside `value`, both complete.
   The runtime does not cross-check the verdict against `value` contents.
8. The `review` entry gate, the `audit_gap` backward edge, `audit_clearance`
   for review, validate, and build, and audit first-pass convergence all read
   the envelope verdict. No consumer parses `produced_outputs` to derive
   `satisfied` or `gaps_found`.
9. Audit-gap implement re-entry receives plan `value`, implement `value`,
   audit `value`, and prior-gap memory through the same `phase_prose` helper.
   Those `value` strings are forwarded verbatim. The re-entry still resolves
   against a refreshed repository checkpoint.
10. Prior-gap memory carries the round number and prior rounds' audit `value`
    strings. `prior_unmet_criteria`, `last_implement_claims`, and `sticky_ids`
    are gone from the model, the declared field list, the briefing map, and
    every prompt directive. Absent memory still omits the non-required
    projection rather than failing the launch.
11. Over-budget prior-gap prose is truncated or dropped under the existing
    UTF-8 and list bounds and emits an observability record, as it does today.
12. Audit and implement directives instruct the round to compare current gaps
    against prior audit `value` strings and re-justify any repeated criterion.
    No prompt renders or references a runtime-derived sticky-id list.
13. In-flight audit outputs, prior-gap-memory projections, and briefings that
    predate this change loud-fail and regenerate in-band after the contract
    bumps, rather than being silently reinterpreted.
14. The prose kit stays shared, not forked: one `$defs` shape that preplan,
    plan, implement, and audit all `$ref`; one handoff contract
    `feature_task_runtime.phase_prose` with no `audit_prose` sibling; one
    parameterized helper (not cloned suites) covering the preplan → plan,
    plan → implement, implement → audit, and audit → implement prose edges.
15. Automated tests cover criteria 2–10, 13, and 14. Prose-handoff tests use
    one helper across all four edges with JSON-shaped strings in `value`. Audit
    prompt tests no longer require inner gap shapes to satisfy a schema gate.
    Criteria 11 and 12 are covered by preserving the existing budget regression
    and by the prompt directives themselves; do not add negative
    "no sticky list is rendered" assertions to chase them.

## Constraints

- Keep the outer envelope. Do not treat the agent stdout as `PhaseOutput`.
- Keep the audit `verdict` required and enum-gated on the envelope. Removing it
  would delete the runtime's routing signal, not a shape gate.
- Enforce that verdict in one place only. Do not leave, add, or reintroduce a
  Kotlin gate that re-checks presence or vocabulary the schema branch already
  covers.
- Reuse `PhaseOutput` and the existing `phaseProseProducedOutputs` def. Do not
  rename them and do not mint an audit-specific variant.
- Do not schema-check the object stuffed inside audit `value`, nor former gated
  fields emitted as legacy sibling keys on `produced_outputs`.
- Do not parse `value` into typed gaps, criterion refs, or severities at any
  Kotlin handoff seam. Forward the raw string; the next agent interprets it.
- Preserve loud-fail for envelope failures, blank `value`, off-vocabulary or
  missing verdict, agent process failure, and contract-version drift.
- Do not require a second LLM format-repair pass.
- Preserve the repository-checkpoint refresh the audit → implement re-entry
  performs today.
- Preserve the existing prior-gap-memory budget enforcement and its
  observability record.
- Preplan, plan, and implement keep the same `value`/`prompt` wire and ungated
  producer path. Their prompts change only where they reference audit output.

## Non-Goals

- Moving `verify_findings` or `implement_fix` onto `PhaseOutput`. Their
  `finding_dispositions` and `repair_receipt` payloads carry runtime-enforced
  coverage semantics (an omitted finding sends the round back; a finding
  reported unresolved twice blocks) that are not agent-authoritative prose.
- Changing review, validate, build, write_history, commit_push, or pr.
- Removing or changing the `audit_gap` backward edge, its per-subtask cap
  policy, or the semantic-loop warning threshold.
- Changing audit convergence *policy*. First-pass convergence keeps its current
  definition; only its verdict source changes.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output` field names.
- Restoring structured criterion-ref injection from audit into implement.
- Remote telemetry breakdowns for audit gap-shape failures.

## Decomposition Rationale

One subtask. Pointing audit at `phase_prose`, collapsing four overlapping
descriptions of gap shape down to one envelope verdict, retargeting the verdict
readers, collapsing prior-gap memory to prose carry-forward, rewriting the audit
and implement directives, and migrating the tests is one handoff. Splitting
"put audit on the shared def" from "stop parsing gaps" would leave a commit
where the schema accepts prose while the Kotlin validators still reject it — a
commit that cannot run.

## Next Path

After audit, the remaining agent-authored shape gates are `verify_findings`
(`finding_dispositions`) and `implement_fix` (`repair_receipt`). Both encode
runtime-enforced coverage obligations rather than agent-authoritative prose, so
neither should adopt the prose kit by momentum. Evaluate each on whether the
runtime still needs to decide something from the structure; where it does, the
answer is a narrower envelope signal like audit's verdict, not a prose
conversion.
