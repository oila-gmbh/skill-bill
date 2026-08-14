# SKILL-191 · Subtask 6 — Verdict-aware register assembly and downstream consumers

## Scope

Carry stage verdicts into the assembled register and teach every consumer to read
them instead of re-deriving judgement.

**Register assembly.** The register keeps its `[F-XXX] Severity | Confidence |
location | description` line format — that format is contract and does not change.
Each finding gains its recorded verdicts as structured fields alongside the line:
`claim_verdict`, optional `scope_disposition`, citations, and any severity
adjustment with its direction and justification.

**Nothing is dropped.** Refuted, unresolved, and out-of-scope findings stay visible in
the output, grouped so a reader sees what survived and what did not. Silent removal
would hide exactly the drift the staged design exists to expose, and would make the
refutation-rate telemetry in subtask 7 unauditable against the output.

**Consumers.** Actionable means `claim_verdict: confirmed` and, when adjudication
ran, a `scope_disposition` of `in_scope` or `spec_deviation`. Update:

- the findings ledger and `triage`, so verdicts are stored and surfaced rather than
  recomputed
- the `implement_fix` handoff, so a remediation round carries actionable findings and
  does not spend a round on a refuted claim
- `blocker_dispositions` under `context:feature-remediation`, so a Blocker that stage 1
  refuted resolves as `superseded` with the verification citation as its evidence
  rather than requiring a fix that has nothing to fix

**Merge.** `skill-bill code-review-merge` and the in-process merge in
`ParallelCodeReviewRunner` coalesce findings that share a root cause and location.
When two lanes' findings coalesce and their verdicts disagree, the merged finding
takes the more conservative outcome — `unresolved` beats `refuted`, `in_scope` beats
`out_of_scope_preexisting` — and both source verdicts stay recorded with their lane
provenance. Coalescing must not silently pick a winner.

## Acceptance Criteria

1. The register preserves the `[F-XXX] Severity | Confidence | location | description` line format unchanged, with verdicts carried as structured fields beside it.
2. Refuted, unresolved, and out-of-scope findings remain present in the register output, grouped by outcome; no finding is dropped by any stage.
3. Actionable is defined as `claim_verdict: confirmed` and, when adjudication ran, `scope_disposition` of `in_scope` or `spec_deviation`, and every consumer applies that one definition.
4. The findings ledger and `triage` store and surface recorded verdicts rather than recomputing them.
5. The `implement_fix` handoff carries only actionable findings, and a refuted finding never opens a remediation round.
6. Under `context:feature-remediation`, a Blocker that stage 1 refuted resolves as `superseded` in `blocker_dispositions`, citing the verification evidence.
7. When coalesced lane findings carry disagreeing verdicts, the merged finding takes the more conservative outcome and both source verdicts are retained with lane provenance.
8. A finding's severity adjustment is visible in the output beside its original severity, not in place of it.

## Non-Goals

- Changing the severity or confidence vocabularies, or the register line format.
- Changing lane coalescing rules beyond verdict reconciliation.
- Telemetry; subtask 7 owns measurement.
- Changing the review phase's `produced_outputs.findings` projection shape beyond the
  added verdict fields.

## Dependency Notes

Depends on subtasks 4 and 5 for the verdicts it carries. Subtasks 8 and 9 depend on
this: both entry points emit through this assembly.

## Validation Strategy

- One test that a refuted finding is present in the output and absent from the
  `implement_fix` handoff. This is the single most load-bearing behaviour in the
  feature: dropping it silently, or acting on it, are both real regressions.
- One test for conservative verdict reconciliation on coalesced findings.
- One test that a refuted Blocker produces a `superseded` disposition with its citation.
- One test that a severity adjustment appears beside the original severity.

## Next Path

Subtask 7 — stage telemetry and measurement.
