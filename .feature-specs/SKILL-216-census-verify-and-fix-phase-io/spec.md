# SKILL-216 — Census I/O for verify_findings and implement_fix

## Philosophy

SKILL-211 through SKILL-214 moved a phase onto `phase_prose` when the next
agent was the consumer and Kotlin was only policing grammar. Audit kept one
machine signal, the envelope `verdict`, because the runtime routes on it.

`verify_findings` and `implement_fix` are not that case. The runtime already
owns the set. Review minted the finding ids. Verification records which of
those ids survive. The fix round must account for every survivor. An omitted
id is silent loss, not a near-miss JSON shape. SKILL-214's next path named
this directly: do not adopt the prose kit by momentum. Where the runtime still
decides something from structure, keep a narrower signal, not a stuffed
`value`.

This skill applies the audit *split*, not the audit *payload*.

- **Generation stays familiar.** Agents still emit `finding_dispositions` and
  `repair_receipt`. Prompts still teach those keys and the same enums.
- **The gated wire is a census.** Each review finding id maps to `verified` or
  `rejected`. Each carried finding id maps to `addressed`, `no_edit_required`,
  or `attempted_unresolved`. That is all the schema requires per entry.
- **Runtime decides from the census, not from essays.** Coverage is set
  equality on ids. Routing reads the envelope `verdict`. The unresolved-twice
  block reads the outcome enum. Kotlin does not `jsonDecode` a prose `value`
  to recover those facts.
- **Former required fields become extra keys.** `reason`, `location`,
  `message`, copied `severity`, `constructs`, `intent`, byte caps, and the
  compact-symbol regex are ignored on the wire, not rejected. Prompts may
  still show them as guidance stuffed beside the census or inside optional
  `value`.

Do not `$ref` `phaseProseProducedOutputs` on either phase.

## Context

These are the last two agent-authored shape gates on the feature-task loop.

`verify_findings` `produced_outputs` is a closed `finding_dispositions` array.
Each entry requires `finding_id`, `disposition`, `reason` (max 280),
`severity`, `location` (max 180), and `message` (max 280), with
`additionalProperties: false`. Optional `selected_boundary_headings` and
`boundary_context_unavailable` drive a two-turn body-delivery loop. Kotlin
parses that whole object, then `validateDispositionCoverage` checks every
review id appears once. `verdictFor` may derive `findings_verified` from
"any verified" and may fall back to `ADVANCE`. Envelope `verdict` is already
required (`findings_verified` | `no_findings_verified`) and already gates
`implement_fix`.

`implement_fix` requires `repair_receipt` with nested `contract_version` 0.2.
Each entry requires `severity`, `outcome`, `constructs` (compact-symbol
regex, optional file basename regex), and `intent` (max 356, no newlines or
backticks), plus outcome-specific `no_edit_reason` / `unresolved_reason` with
the same pattern. Coverage keys on `finding_id` against the carried set
(review findings minus verification-refuted ids). An omitted carried id
retries the round. `attempted_unresolved` twice blocks for an operator.

The 2026-08-24 repair-receipt decision already shows the failure mode: a
correct round blocked because coverage measured the wrong set, and construct
or byte-cap near-misses discard work the tree already holds. The census is
the part Kotlin must keep. The rest is the grammar cop SKILL-211 through
SKILL-214 removed everywhere the next reader was an agent.

What the runtime actually consumes:

- Envelope `verdict` on `verify_findings` for the `implement_fix` entry gate
  and the `review_fix` edge.
- Per-id `verified` | `rejected` for the unaddressed-findings ledger, the
  carried-fix list, and coverage.
- `selected_boundary_headings` / `boundary_context_unavailable` for the
  existing two-turn body-delivery protocol.
- Per-id repair `outcome` for coverage and the unresolved-twice block.

It does not need agent-copied severity, location, or message. Those already
live on the review finding. It does not need construct symbols or intent
strings to decide whether the round accounted for a finding.

## Intended Outcome

Keep the existing produced_outputs keys. Slim them to a census.

**`verify_findings`.** Envelope still requires `verdict` of
`findings_verified` or `no_findings_verified`. `produced_outputs` still
requires `finding_dispositions`. Each item requires only `finding_id` and
`disposition` (`verified` | `rejected`). Extra keys on the item and on
`produced_outputs` are ignored. Kotlin coverage remains set equality against
the preceding review pass (exactly one disposition per review id, no foreign
ids, no duplicates).

`selected_boundary_headings` and `boundary_context_unavailable` stay
readable when present. The two-turn body-delivery protocol does not change.
Absence means no selection, not a schema failure.

Envelope `verdict` is the sole routing signal, same rule as audit after
SKILL-214. `verdictFor(PHASE_VERIFY_FINDINGS, …)` maps the wire verdict. It
does not derive the verdict from "any verified" and it does not fall back to
`ADVANCE`. Schema remains the only presence and vocabulary gate. Kotlin does
not cross-check the verdict against census contents. `findings_verified` with
zero `verified` rows still completes. The entry gate still fires. The carried
set for `implement_fix` is the verified ids, so that round's owed census is
empty and an empty `repair_receipt.entries` covers it.

Ledger rows for rejected findings take identity, severity, location, and
message from the review finding. Optional census `reason` is persisted when
present. Over-long reason is truncated under an existing or named UTF-8 bound
with an observability record, not rejected as a shape failure. Missing reason
does not retry the phase.

**`implement_fix`.** `produced_outputs` still requires `repair_receipt`. The
gated inner shape is `entries` of `{finding_id, outcome}` with
`outcome` in `addressed` | `no_edit_required` | `attempted_unresolved`. Nested
`contract_version` bumps from `0.2` to `0.3` in lockstep with
`FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION`. Extra keys
(`constructs`, `intent`, `severity`, `label`, `text`, `no_edit_reason`,
`unresolved_reason`, aliases) are ignored, not rejected. Finding-id aliases
(`finding_ref`, `id`, `ref`) remain accepted at parse as salvage.

Coverage still keys on `finding_id` against
`featureTaskRuntimeCarriedFindings` (stable refs, verification-refuted ids
dropped). Omit still retries. `attempted_unresolved` twice still blocks.
Optional `unresolved_reason` / `no_edit_reason` forward into operator or
retry text when present, with truncation plus observability rather than a
pattern or byte-cap rejection.

Prompts still show the former fat objects as *recommended* content. The
required example is the census. Directives stop threatening rejection for
symbol regex, intent backticks, or reason length.

Phase-output envelope `contract_version` bumps `0.5` to `0.6` with the Kotlin
constant. In-flight `0.5` verify and implement_fix outputs, and `0.2` repair
receipts in durable review state, loud-fail and regenerate in-band.

No `verify_prose`, no `fix_prose`, no fifth `allOf` copy of
`phaseProseProducedOutputs`. Preplan, plan, implement, and audit stay on the
shared prose kit unchanged.

## Acceptance Criteria

1. `verify_findings` and `implement_fix` `produced_outputs` do not `$ref`
   `phaseProseProducedOutputs`. The shared prose kit still covers only
   preplan, plan, implement, and audit.
2. A completed `verify_findings` whose `finding_dispositions` is only
   `{finding_id, disposition}` per review finding, plus a valid envelope
   `verdict`, advances. Coverage still rejects omitted, duplicate, or foreign
   ids.
3. Extra keys on a disposition item (`reason`, `severity`, `location`,
   `message`, nested wrappers) and leftover sibling keys on
   `produced_outputs` are ignored, not rejected.
4. Missing or off-vocabulary envelope `verdict` on `verify_findings` fails
   loudly at the schema branch only. Kotlin does not re-check vocabulary, does
   not derive the verdict from the census, and does not fall back to
   `ADVANCE`.
5. `findings_verified` with zero `verified` rows, and `no_findings_verified`
   with some `verified` rows, both complete. The `implement_fix` entry gate
   and `review_fix` edge still read the envelope verdict. The carried-fix list
   still reads `verified` ids from the census.
6. When `selected_boundary_headings` is present it still drives the existing
   body-delivery continue loop and provenance checks. When absent, verify
   settles without that loop. `boundary_context_unavailable: true` still
   forbids a non-empty heading list.
7. Rejected ledger rows use the review finding's id, severity, location, and
   message. Optional census `reason` persists when present. Over-long reason
   truncates with an observability record. Missing reason does not retry
   verify.
8. A completed `implement_fix` whose `repair_receipt.entries` is only
   `{finding_id, outcome}` per carried finding advances. Nested
   `contract_version` is `0.3`. Extra keys including `constructs`, `intent`,
   and reason fields are ignored.
9. Carried-set coverage is unchanged in policy: refuted findings are not
   owed an entry; an omitted *carried* id retries; `attempted_unresolved` on
   the same id in two rounds blocks. No consumer requires constructs or
   intent for those decisions.
10. Compact-symbol regex, intent/reason newline-and-backtick patterns, and
    the closed `additionalProperties: false` item schemas no longer reject
    `verify_findings` or `implement_fix`. A surviving test that asserts those
    rejections is a failure of this skill.
11. Prompts and projection-shape examples for both phases show the census as
    the required produced_outputs object. Former fat fields are guidance, not
    a gated example. No prompt claims a construct or byte-cap failure will
    retry the round.
12. Phase-output contract is `0.6`. Repair-receipt nested contract is `0.3`.
    In-flight `0.5` outputs and `0.2` receipts loud-fail and regenerate
    in-band rather than being coerced.
13. Automated tests cover criteria 2 through 9 and 12. Coverage tests share
    one helper for "omitted / duplicate / foreign id" rather than cloned
    suites. Schema tests, not a Kotlin second gate, own missing and
    off-vocabulary envelope verdict. Criteria 10 and 11 are covered by
    deleting the old rejection assertions and by the prompt text itself.

## Constraints

- Keep the outer phase envelope. Do not treat agent stdout as the census.
- Keep envelope `verdict` required on `verify_findings`. Removing it would
  delete the `implement_fix` routing signal.
- Enforce that verdict in one place, the schema branch. Do not reintroduce a
  Kotlin vocabulary check or a derived-from-census override.
- Keep `finding_dispositions` and `repair_receipt` as the produced_outputs
  keys. Do not mint `finding_census`, `verify_prose`, or `fix_prose`.
- Do not `$ref` `phaseProseProducedOutputs` for these phases.
- Do not parse optional `value` to recover ids, dispositions, or outcomes.
- Preserve loud-fail for envelope failures, missing census key, missing
  finding_id, off-vocabulary enums, omitted coverage ids, agent process
  failure, and contract-version drift.
- Preserve the two-turn boundary-heading body-delivery protocol.
- Preserve verification-refuted findings as not carried.
- Do not require a second LLM format-repair pass.
- Preplan, plan, implement, and audit keep their `value` / `prompt` wire.

## Non-Goals

- Moving `verify_findings` or `implement_fix` onto `PhaseOutput`.
- Changing review, audit, validate, build, write_history, commit_push, or pr.
- Changing the `review_fix` edge cap, `ADVANCE` on exhaustion, or
  Blocker-or-Major advance policy.
- Changing audit-gap topology or prior-gap memory.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output`.
- Restoring construct-symbol or intent injection into later phases.
- Remote telemetry breakdowns for the retired shape failures.

## Decomposition Rationale

One subtask. Slimming both remaining gates is one producer-consumer handoff.
The fix round's owed set is the verify census. Splitting "slim verify" from
"slim implement_fix" leaves a commit where one side still rejects the fat
object the other just stopped requiring. Schema, Kotlin coverage, ledger,
prompts, and tests have to move together.

## Next Path

Remaining agent-authored produced_outputs that are still closed objects sit
on finalization phases (`history_result`, validate/build receipts). Evaluate
each the same way. If Kotlin measures the tree or a pack gate itself, keep
that measurement. If it only polices agent grammar for the next agent, slim
or drop the gate. Do not put those phases on `phase_prose` by default.
