# SKILL-202 · Subtask 4 — Surface verification dispositions

Parent: `spec.md`

## Scope

Make every operator-visible and prompt-visible surface describe the new flow:
one review pass, per-finding verification, one fix round. Rejected findings
become retrievable with their reasons, and the accounting that died with the
remediation loop stops being reported.

Files in play:

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/UnaddressedFindingsLedgerService.kt`
  and `.../GoalRunnerLedgerRecorder.kt` — record dispositions and reasons.
- the `goal findings` CLI command and its result mapping.
- `goal status` and `goal watch` projection: `fix_iterations`,
  `re_attempt_causes`, `phase_attempts`, and any `review_fix` naming.
- telemetry and review-stats surfaces that count review passes or remediation
  rounds.
- `skills/` prose: the `bill-feature-goal` review contract, its audit-first
  ordering paragraph, and any platform-pack review prose that describes
  remediation passes or Blocker-and-Major reopening.

## Preferred Approach

Extend the ledger entry with the verification disposition and its reason rather
than adding a second store. A rejected finding is a ledger row like an
unaddressed one, distinguished by disposition, so `goal findings` stays the one
location-bearing surface and the goal-facing summaries stay path-free.

Report what now exists and drop what cannot happen. `fix_iterations` for
`review_fix` and `re_attempt_causes: backward_edge` from the review path are
gone; a single `verified_fix` round either ran or did not. Keep a decode path for
existing durable records where removing the field would break them, and say so in
a comment.

Rewrite the prose to match the topology instead of patching sentences. The
`bill-feature-goal` review contract currently states that remediation continues
while Blocker or Major findings survive, that later passes run inline against the
remediation delta, and that crossing iteration 3 warns and continues. All three
statements become false.

## Acceptance Criteria

1. `skill-bill goal findings --issue-key <KEY>` shows each finding's verification
   disposition and, for a rejected finding, the reason it was rejected.
2. Goal-facing summaries stay path-free: they carry subtask id, disposition
   counts, severity, and concise text, and no path, line number, or diff hunk.
3. `goal status` and `goal watch` report whether the single verified fix round
   ran, and no longer report review-pass or remediation-round counters that
   cannot be produced.
4. Telemetry and review-stats surfaces count one review pass per subtask and
   report verification dispositions instead of remediation rounds.
5. No skill or pack prose claims that remediation continues while findings
   survive, that Blocker and Major reopen `implement_fix`, that Minor and Nit
   merely advance, or that an advisory threshold of 3 exists on the review path.
6. The `bill-feature-goal` review contract describes: one pass, per-finding
   verification against intent and boundary memory, one fix round covering every
   verified finding at any severity, then validate.
7. Existing durable ledger records stay readable, and a row without a disposition
   renders as such rather than as verified.
8. The repository validation gate passes.

## Non-Goals

- Changing review lane assembly, evidence brokering, or the review packet
  contract.
- Adding a new CLI command or a new report format.
- Cross-goal aggregation of rejection reasons.

## Dependencies

Subtask 2. Dispositions must exist before a surface can report them. Independent
of subtask 3; a disposition with no cited headings renders as one that relied on
none.

## Validation Strategy

CLI result-mapping tests for verified, rejected, and legacy rows. A projection
test asserting the removed counters are absent and the round indicator is
present. A prose check that no surface still names the removed loop. Run the
runtime-owned validation gate.

## Next Path

Parent goal completion. Run `skill-bill goal SKILL-202`.
