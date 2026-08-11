# SKILL-178 · Subtask 1 — Widen the domain severity gates to Blocker + Major

## Scope

The two severity predicates in
`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimeReviewVerdict.kt`
are the single source of the Blocker-only rule:

- `FeatureTaskRuntimeReviewSeverity.requiresRemediation` — currently `this == BLOCKER`
- `FeatureTaskRuntimeReviewSeverity.blocksAdvance` — currently `this == BLOCKER`

Both become "Blocker or Major". Everything downstream derives from them:
`FeatureTaskRuntimeReviewVerdict.verdict` (CHANGES_REQUESTED when any finding
requires remediation), `remediationFindings`, and `unresolvedFindings`.

Note that the file's KDoc *already claims* "Blocker and Major both require
remediation in the fix pass" while the code says Blocker only — the comment
describes the target state and the code does not. Reconcile both; do not treat the
existing comment as evidence the behaviour is already correct.

Also in scope, the second implementation of the same rule for durable goal state in
`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt`:

- the private `blocksAdvance(unresolvedFindingCount, findings)` helper (~line 694),
  which today asks `findings.any(GoalSubtaskReviewCompactFinding::isBlocker)`. It
  needs the compact-finding equivalent of "blocks advance", not "is blocker". The
  empty-findings case must keep its current meaning: a compact summary carrying a
  positive unresolved count with no itemised findings stays blocking.
- `GoalSubtaskReviewCompactFinding.isBlocker` and any sibling severity predicate —
  add or widen the predicate the helper needs.
- the `require` invariants and their messages around `REVIEW_CAP_REACHED` (~line 408)
  and `PAUSED` (~line 420), which currently say "unresolved Blocker findings" and
  "requires an unresolved Blocker disposition". The messages must state the widened
  rule, because an operator reads them verbatim on a schema failure.

Keep the durable artifact keys (`blocker_dispositions`, `unresolved_finding_count`)
unchanged — renaming a persisted key requires a migration and is out of scope. Where
the Kotlin-side name is now semantically wrong, correct the KDoc rather than the
wire key.

Do not change severity parsing, the finding model, or the ledger.

## Acceptance Criteria

1. `FeatureTaskRuntimeReviewSeverity.requiresRemediation` is true for `BLOCKER` and `MAJOR`, and false for `MINOR` and `NIT`.
2. `FeatureTaskRuntimeReviewSeverity.blocksAdvance` is true for `BLOCKER` and `MAJOR`, and false for `MINOR` and `NIT`.
3. `FeatureTaskRuntimeReviewVerdict.verdict` resolves to `CHANGES_REQUESTED` when the findings contain any Blocker or any Major, and to `APPROVED` when they contain only Minor and Nit findings or no findings.
4. `remediationFindings` and `unresolvedFindings` each return the Blocker and Major findings in their original order.
5. The private `blocksAdvance` helper in `GoalSubtaskReviewState.kt` treats an itemised finding list as blocking when it contains a Blocker or a Major, and continues to treat a positive unresolved count with an empty finding list as blocking.
6. The `REVIEW_CAP_REACHED` and `PAUSED` invariant messages state the Blocker-or-Major rule rather than "Blocker".
7. Every durable artifact key persisted by `GoalSubtaskReviewState` is unchanged, so an existing durable record still decodes without migration.
8. The KDoc in `FeatureTaskRuntimeReviewVerdict.kt` describes the implemented rule; no comment claims a Blocker-only gate.

## Non-Goals

- Changing the remediation-delta scope (subtask 2).
- Changing pause or operator-decision behaviour (subtask 3).
- Updating governed skill content or the parity locks (subtask 4).
- Renaming persisted artifact keys or adding a migration.
- Promoting Minor or Nit to blocking.

## Dependencies

None. This subtask is the foundation the other three build on.

## Validation Strategy

- Unit-test both predicates across all four severities.
- Unit-test `verdict`, `remediationFindings`, and `unresolvedFindings` for a mixed
  finding list (1 Blocker, 2 Major, 4 Minor) and for a Minor/Nit-only list, asserting
  the Minor/Nit-only list is `APPROVED` with empty remediation and unresolved lists.
- Unit-test the `GoalSubtaskReviewState` helper for: Major-only itemised findings
  (blocking), Minor-only itemised findings (not blocking), and a positive unresolved
  count with an empty list (blocking).
- Round-trip an existing durable `GoalSubtaskReviewState` artifact map through
  `fromArtifactMap`/`toArtifactMap` and assert byte-identical keys.
- Expect existing tests asserting the Blocker-only rule to fail. That is the signal
  the gate moved; leave them failing for subtask 4 rather than deleting them, and
  list them in the handoff.
- Build and test the affected modules.

## Next Path

Subtask 2 widens the remediation-delta finding union.
