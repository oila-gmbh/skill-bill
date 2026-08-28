# SKILL-214 · Subtask 1 — Replace audit gaps with phase prose

Parent spec: `spec.md`

## Scope

Put audit on the shared `phase_prose` kit. The former `gaps` /
`non_blocking_findings` object moves inside `value` as structured prose; the
runtime forwards it verbatim and the implement gap re-entry interprets it. The
envelope `verdict` stays required and enum-gated because it is the runtime's
routing signal, not agent prose.

In scope:

- Add audit to the phase-output `allOf` branch that `$ref`s
  `phaseProseProducedOutputs`. Preplan, plan, implement, and audit share one
  def. Bump the phase-output contract version and its Kotlin constant together.
- Keep the `phase_id: audit` branch that requires a top-level `verdict` of
  `satisfied` or `gaps_found`. Remove that branch's `produced_outputs`
  constraints: `required: [gaps]`, the `not: anyOf` legacy-key blacklist, the
  `gaps` and `non_blocking_findings` property schemas, and the two
  verdict-to-array coherence branches.
- Remove the `unmetCriterion` `$defs` once nothing references it.
- Reduce `FeatureTaskRuntimeOutputVerification` audit handling to the envelope
  verdict: delete `auditGapPayloadError`, `auditVerdictFrom`,
  `auditCriterionGapFromEntry`, `auditCriterionGapMessage`,
  `unmetAuditCriteria`, `canonicalAuditCriterionRefs`, the `AUDIT_CRITERION_REF`
  regex, the alias/legacy-key rejections, and the satisfied/gaps_found payload
  checks. Delete `FeatureTaskRuntimeAuditVerdict`,
  `FeatureTaskRuntimeAuditCriterionGap`, and `FeatureTaskRuntimeAuditSeverity`
  where they become unreachable, and the
  `FeatureTaskRuntimeVerificationSignalKeys` audit constants (`AUDIT_GAPS`,
  `AUDIT_UNMET_CRITERIA`, `AUDIT_NON_BLOCKING_FINDINGS`,
  `AUDIT_FAILING_CRITERIA_REJECTED_ALIAS`) once nothing reads them.
- `verdictFor(PHASE_AUDIT, …)` maps the wire verdict and must not fall back to
  `ADVANCE` for audit: with the schema guaranteeing the field, a fallback would
  silently advance a run past an unreadable verdict.
- Delete `FeatureTaskRuntimeVerificationGateReasons.auditVerificationSignal`
  entirely and drop its call from `outputVerificationGateReason`. Do not keep a
  reduced verdict-only version: the schema branch already rejects a missing or
  off-vocabulary verdict, and the function's verdict branches are unreachable
  behind the schema's `required: [gaps]` today anyway.
- Delete `FeatureTaskRuntimeStrictWireMapping.kt`. Every symbol in it
  (`AUDIT_REPAIR_*_KEYS`, `requireExactWireKeys`, `requiredArray`) is declared
  and referenced nowhere. Confirm zero references before removing the file.
- Retarget `auditClearanceStatus` in `FeatureTaskRuntimeHandoffProjectionValidator`
  to the envelope verdict for both `clearance_status` and `verdict`.
- Retarget `auditRemediationProjections()` to deliver plan, implement, and audit
  prose plus prior-gap memory. Remove the `audit_repair_request` projection's
  `unmet_criteria` field, `FeatureTaskRuntimeHandoffProjectionInputs.unmetCriterionRefs`,
  `durableAuditRepairProjectionFields`, and `reentryGapCriteria` plumbing.
  Preserve the `REFRESH_FROM_REPOSITORY` checkpoint policy on the audit prose
  edge that `audit_repair_request` carried.
- Collapse `FeatureTaskRuntimePriorGapMemory` to `round` plus a bounded list of
  prior rounds' audit `value` strings. Remove `prior_unmet_criteria`,
  `last_implement_claims`, `sticky_ids`, the sticky two-audit intersection in
  `FeatureTaskRuntimeRunLoop.priorGapMemoryFor`, and the dead
  `lastImplementClaims()` stub. Keep `boundPriorGapNotes` bounds and its
  truncation observability record. Update the briefing map and its contract
  version.
- Rewrite the audit directive to show the former gaps object as the content of
  `value`, and rewrite `priorGapMemoryRemediationDirective`,
  `auditPhaseTaskDirective`, `auditRoundScopeAddendum`, and
  `auditProducedOutputsSkeleton` so recurrence re-justification reads prior
  audit `value` strings instead of a runtime-derived sticky list.
- Update the implement gap-re-entry directive to read audit `value` as
  structured prose.
- Migrate tests: extend the shared prose-handoff helper to the audit → implement
  edge rather than cloning a suite; drop fixtures and assertions that exist only
  to satisfy the removed gap-shape gates.
- Run `./install.sh` if any skill source or rendered pointer changes.

Out of scope: `verify_findings`, `implement_fix`, review, and every
finalization phase; the `audit_gap` edge topology and caps; audit convergence
policy.

## Acceptance Criteria

1. Audit `produced_outputs` `$ref`s the shared `phaseProseProducedOutputs` def;
   preplan, plan, implement, and audit share one `$defs` shape and one
   `feature_task_runtime.phase_prose` contract with no `audit_prose` sibling.
2. A completed audit carrying only a non-blank `value` plus a valid envelope
   verdict advances, and the implement gap re-entry briefing contains that
   string unchanged.
3. Legacy keys beside `value` (`gaps`, `unmet_criteria`, `failing_criteria`,
   `audit_repair_plan`, `carried_gap_dispositions`, `blast_radius_inspection`,
   `prior_gap_dispositions`) are ignored, not rejected.
4. Malformed inner content in a non-blank `value` still advances: `AC-7`,
   backticks or newlines in a note, a note over 1024 characters, a nested
   wrapper, a `minor` severity in the gaps list, or non-JSON text.
5. Blank or missing `value` on `status: completed` retries or blocks audit, and
   the gap re-entry does not launch.
6. A completed audit with a missing or off-vocabulary top-level `verdict` fails
   loudly and re-enters audit, enforced only by the `phase_id: audit` schema
   branch. `auditVerificationSignal` and `FeatureTaskRuntimeStrictWireMapping.kt`
   are deleted and `outputVerificationGateReason` has no audit branch.
7. `gaps_found` with no gaps inside `value`, and `satisfied` with gap-looking
   text inside `value`, both complete; the runtime does not cross-check them.
8. The `review` entry gate, the `audit_gap` backward edge, `audit_clearance`
   for review/validate/build, and first-pass convergence all read the envelope
   verdict; no consumer parses `produced_outputs` for it.
9. Audit-gap implement re-entry receives plan, implement, and audit `value`
   verbatim plus prior-gap memory through `phase_prose`, and still resolves
   against a refreshed repository checkpoint.
10. Prior-gap memory carries `round` and prior rounds' audit `value` strings
    only; the three removed fields are absent from the model, declared field
    list, briefing map, and prompts, and absent memory still omits the
    non-required projection.
11. Over-budget prior-gap prose truncates or drops under the existing bounds
    and emits the observability record.
12. Audit and implement directives require re-justification of a repeated
    criterion by comparing against prior audit `value` strings, and no prompt
    renders a runtime-derived sticky-id list.
13. In-flight audit outputs, prior-gap-memory projections, and briefings that
    predate the contract bumps loud-fail and regenerate in-band.
14. Prose-handoff tests use one parameterized helper across preplan → plan,
    plan → implement, implement → audit, and audit → implement.

## Non-Goals

- Moving `verify_findings` or `implement_fix` onto `PhaseOutput`.
- Changing review, validate, build, write_history, commit_push, or pr.
- Changing the `audit_gap` edge topology, caps, or warning threshold.
- Changing first-pass convergence policy beyond its verdict source.
- Renaming `PhaseOutput` or unifying it with `AgentPhaseOutput.output`.

## Dependency Notes

Depends on SKILL-211, SKILL-212, and SKILL-213, all landed on `main`: the
`phase_prose` contract, the shared `phaseProseProducedOutputs` def, and the
`producedProjectionKindFor` null-for-every-phase state already exist. This
subtask adds the fourth matrix row and unwinds audit's gated shape. No new
dependency outside the repository.

## Validation Strategy

- `./gradlew compileKotlin` from `runtime-kotlin` for buildability, then the
  pack collect-all gate for the full check.
- Contract-version parity tests must fail before and pass after the paired
  bump of the phase-output schema and its Kotlin constant.
- Acceptance tests: prose advance with legacy keys present, prose advance with
  malformed inner content, blank-`value` block, missing and off-vocabulary
  verdict rejection, verdict-versus-`value` non-cross-check both directions.
- Rejection tests: no test may assert that a gap-shape near-miss is rejected;
  removing such assertions is part of the change, and a surviving one is a
  failure of this subtask. Exactly one test asserts missing-verdict rejection
  and one asserts off-vocabulary-verdict rejection, both against the schema
  validator; no sibling test asserts the same rejection at a Kotlin gate.
- Handoff tests exercise all four prose edges through one parameterized helper
  and assert byte-identical forwarding of `value`.
- Quarantine/regeneration test for a pre-bump audit output and a pre-bump
  prior-gap-memory projection.

## Next Path

Evaluate `verify_findings` and `implement_fix` on whether the runtime still
needs to decide something from their structure. Where it does, the answer is a
narrower envelope signal like audit's verdict, not a prose conversion.
