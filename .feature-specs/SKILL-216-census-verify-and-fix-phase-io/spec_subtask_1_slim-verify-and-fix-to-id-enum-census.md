# SKILL-216 · Subtask 1 — Slim verify and fix to an id-enum census

Parent spec: `spec.md`

## Scope

Replace the closed `finding_dispositions` and `repair_receipt` item schemas
with a census the runtime already has the right-hand side for. Do not join
`phase_prose`.

In scope:

- Bump phase-output `contract_version` from `0.5` to `0.6` with
  `FEATURE_TASK_RUNTIME_CONTRACT_VERSION`. Bump nested repair-receipt
  `contract_version` from `0.2` to `0.3` with
  `FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION`.
- `phase_id: verify_findings` keeps `required: [verdict]` and the two-value
  enum. Slim `finding_dispositions` items to `required: [finding_id,
  disposition]`. Drop required `reason`, `severity`, `location`, `message`.
  Item `additionalProperties` must not reject former fat fields. Keep
  `selected_boundary_headings` and `boundary_context_unavailable` readable
  under the existing provenance rules when present.
- `FeatureTaskRuntimeFindingVerificationDisposition` stops requiring
  `reason`, `location`, and `message` in `init` / `fromArtifactMap`. Coverage
  still runs through `validateDispositionCoverage` on ids only.
- `verdictFor(PHASE_VERIFY_FINDINGS, …)` maps the envelope verdict only.
  Delete the derive-from-census path in `findingVerificationVerdict` and the
  `ADVANCE` fallback for this phase. Do not add a Kotlin re-check of
  vocabulary the schema already gates, and do not cross-check verdict against
  census contents.
- Ledger writes for rejected findings copy identity fields from the review
  finding. Persist optional census `reason` when present. Truncate over-budget
  reason with an observability record. Do not retry verify for a missing
  reason.
- Keep the body-delivery continue loop. It already no-ops when coverage
  fails or when no headings are selected. Confirm a census-only disposition
  (no `selected_boundary_headings`) settles without that loop.
- `phase_id: implement_fix` still requires `repair_receipt`. Slim
  `$defs/repairReceipt` / `repairReceiptEntry` so each entry requires
  `finding_id` (aliases still salvage at parse) and `outcome`. Drop required
  `severity`, `constructs`, `intent`, and the outcome-specific required
  reason fields. Drop the compact-symbol and intent/reason pattern gates from
  the schema and from `FeatureTaskRuntimeRepairReceipt` decode. Extra keys
  ignored.
- Coverage helpers stay. `featureTaskRuntimeCarriedFindings`, omitted-id
  retry, and unresolved-twice block still key on `finding_id` and `outcome`.
  No remaining call site may demand constructs or intent to settle a
  completed round.
- Rewrite verify and implement_fix directives and
  `FeatureTaskRuntimePhaseProjectionShapes` so the required example is the
  census. Former fat fields are guidance. Remove prompt text that says a
  symbol regex or byte cap will reject the round.
- Migrate tests. Delete assertions that a construct, intent, backtick, or
  reason-length near-miss is rejected. Keep omitted / duplicate / foreign id
  coverage. One helper, not cloned suites. Schema-only tests for missing and
  off-vocabulary verify verdict. Quarantine or regeneration coverage for a
  pre-bump verify output and a `0.2` repair receipt.
- Run `./install.sh` if any skill source or rendered pointer changes.

Out of scope: `phase_prose` for these phases; review, audit, validate, build,
write_history, commit_push, pr; `review_fix` cap and exhaustion behavior;
audit-gap.

## Acceptance Criteria

1. Neither phase `$ref`s `phaseProseProducedOutputs`.
2. Census-only `finding_dispositions` plus a valid envelope verdict advances
   verify, and coverage still names omitted, duplicate, and foreign ids.
3. Extra keys on disposition items and on `produced_outputs` are ignored.
4. Missing or off-vocabulary verify `verdict` fails at the schema branch
   only. Kotlin does not derive that verdict from the census and does not
   fall back to `ADVANCE`.
5. Verdict-versus-census mismatch both directions still completes. Entry gate
   and `review_fix` read the envelope. Carried findings read `verified` ids.
6. Present `selected_boundary_headings` still runs body delivery. Absent
   headings settle without it.
7. Rejected ledger rows do not depend on agent-copied severity, location, or
   message. Optional reason persists or truncates. Missing reason does not
   retry.
8. Census-only `repair_receipt.entries` plus nested `contract_version` `0.3`
   advances implement_fix. Extra keys including `constructs` and `intent`
   are ignored.
9. Refuted ids are not owed an entry. Omitted carried ids retry.
   `attempted_unresolved` twice blocks. Constructs and intent are not part of
   those decisions.
10. No remaining test asserts construct-regex, intent-pattern, or reason-cap
    rejection for these phases.
11. Prompts show the census as the required shape and do not claim those
    retired gates.
12. Envelope contract `0.6` and nested receipt `0.3`. Pre-bump records
    loud-fail and regenerate in-band.

## Non-Goals

- Moving either phase onto `PhaseOutput`.
- Changing review, audit, or finalization phases.
- Changing `review_fix` topology, cap, or exhaustion.
- Parsing optional `value` for ids or enums.

## Dependency Notes

Depends on SKILL-214 (and through it SKILL-211 through SKILL-213), landed on
`main`. The shared prose kit exists and must stay off these two phases. Also
depends on SKILL-202 verification and the 2026-08-24 carried-set rule that
drops `verification_disposition = rejected` from repair coverage. No new
dependency outside the repository.

## Validation Strategy

- `./gradlew compileKotlin` from `runtime-kotlin` for buildability, then the
  pack collect-all gate for the full check.
- Contract-version parity tests fail before and pass after the paired bumps.
- Acceptance tests: census-only verify advance, extra-key ignore, omitted and
  foreign id coverage, envelope-verdict-only routing, verdict/census
  non-cross-check both directions, body-delivery still runs when headings are
  present, census-only repair advance, extra-key ignore on constructs,
  omitted carried retry, unresolved-twice block, refuted id not owed.
- Rejection tests: no test may assert construct, intent, backtick, or
  reason-length rejection. Removing those assertions is part of the change.
  Exactly one schema test for missing verify verdict and one for
  off-vocabulary verify verdict. No sibling Kotlin-gate copy of those two.
- Quarantine or regeneration test for a `0.5` verify output and a `0.2`
  repair receipt.

## Next Path

Parent next path. Finalization receipts next, evaluated as measurement versus
grammar, not converted onto `phase_prose` by default.
