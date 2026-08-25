# SKILL-208 subtask 1 — Demote payload shape policing to diagnostics

## Scope

Stop the rules that police the *inside* of a phase payload from deciding whether
the phase advances. This is the relief commit: 278 of the 358 rows in
`rejected_output_diagnostics` are `implement_fix` alone (172 repair-receipt
coverage, 106 phase-output-schema), and their causes are `text` over
`maxLength: 256`, `constructs[].symbol` failing its regex, `finding_ref` rejected
as an unknown property, and "must include one entry for every finding carried
into this round". None of those is a disagreement about meaning.

The envelope stays. Routing stays exactly as it is today, reading the same
fields from the same places. What changes is that a payload whose entries are
mis-shaped records the finding and advances instead of consuming a correction
budget and blocking.

In scope:

- Repair-receipt rules become diagnostics: entry field length and pattern caps,
  the `constructs[].symbol` shape, alias handling for the finding identity, and
  the coverage rule in `featureTaskRuntimeRepairReceiptCoverageRejection`. A
  round that named its outcomes in prose or overran a cap settles and records;
  the next verification pass re-decides from the tree.
- Per-entry constraints inside `produced_outputs` become diagnostics across the
  phase-output schema: `unmetCriterion.note` length and pattern,
  `repairPlanEntry` and `repairReceiptEntry` field rules,
  `finding_dispositions` entry field rules, and the key-alias and key-placement
  rules in `FeatureTaskRuntimeOutputVerification` (`failing_criteria`,
  `unmet_criteria`) and `FeatureTaskRuntimePhaseOutputEnvelopeWalker`.
- `FeatureTaskRuntimePhaseOutputStructuralRepair` and
  `FeatureTaskRuntimePhaseOutputDuplicateKeyMerge` stop being on the path that
  can end a phase: their outcome is recorded, never the reason a result is
  discarded or a correction budget is charged.
- Every demoted rule emits an observability record naming the phase, the rule,
  and the pointer, so the diagnostics that drove this subtask keep accruing after
  the gate stops blocking.

Deliberately NOT in scope, and load-bearing that they stay: the fields the
runtime routes on keep their present requirements until subtask 3 derives them.
`audit` still owes a `verdict` and a `gaps` key, `verify_findings` still owes a
`verdict` and a `finding_dispositions` key, and `implement_fix` still owes a
`repair_receipt` container. Relaxing those before derivation lands would let an
audit that found gaps in prose fall through `auditVerdictFrom` to `ADVANCE` and
skip its remediation edge, which is the one failure this whole feature must not
introduce.

Also out of scope: the envelope and verbatim prose output (subtask 4), agent
echoes and runtime-minted facts (subtask 2), and deleting the retired rules
(subtask 5).

## Acceptance Criteria

1. A phase whose payload entries violate a demoted rule — an over-length
   `text` or `note`, a non-conforming `constructs[].symbol`, an aliased finding
   identity, a misplaced or duplicated key — settles with its recorded output
   intact instead of blocking or consuming a correction budget.
2. An `implement_fix` round whose repair receipt does not carry one entry per
   carried finding settles and records the shortfall instead of being sent back;
   the carried findings still reach the next verification pass, which re-decides
   them from the tree.
3. The fields the runtime routes on keep their current requirements: an `audit`
   without a `verdict` or a `gaps` key, and a `verify_findings` without a
   `verdict` or a `finding_dispositions` key, still fail as they do today.
4. Absent, blank, or unparseable phase output stays a loud failure with the
   offending phase id, and the root-level envelope requirements are unchanged.
5. Every demoted rule emits an observability record carrying the phase id, the
   rule name, and the rejection pointer, per `docs/observability-policy.md`.
6. No durable record shape changes: a workflow blocked under a now-demoted rule
   resumes and completes without a hard reset, and every record already written
   still validates.
7. Structural repair and duplicate-key merge no longer decide a phase outcome;
   their result is recorded and the phase settles on the text it returned.

## Non-Goals

- Relaxing the verdict, gaps, or finding-dispositions requirements the runtime
  currently routes on (subtask 3 removes those, once derivation replaces them).
- Changing how phase input is composed or making phase output a verbatim string
  (subtask 4).
- Moving any fact off the agent's output or changing mutating-phase
  reconciliation (subtask 2).
- Deleting the demoted rules, the schema files, the repair passes, or the
  planning projection contracts (subtask 5).
- Changing the phase DAG, loop caps, correction budgets, or ceremony scaling.

## Dependency Notes

- Depends on: none. This is deliberately the first subtask.
- Independent of: subtask 2.
- Unblocks: nothing structurally. It ships the relief early and can be reverted
  on its own if a demoted rule turns out to have been load-bearing.

## Validation Strategy

- Targeted tests reconstructed from the durable diagnostics: an `implement_fix`
  receipt with an over-length `text`, one with a path-shaped
  `constructs[].symbol`, one carrying `finding_ref` where `finding_id` is
  canonical, and one covering none of its three carried findings — each settles
  with a record rather than blocking.
- A rejection test that an `audit` missing its `verdict` still fails, proving the
  routing fields were not relaxed with the rest.
- A resume test that a workflow persisted as blocked under a demoted rule
  resumes without a reset.
- Compile the affected runtime modules.

## Next Path

Subtask 2 moves the facts the runtime can observe off the agent's output, which
is what lets subtask 3 derive a review verdict without a `findings` array.
