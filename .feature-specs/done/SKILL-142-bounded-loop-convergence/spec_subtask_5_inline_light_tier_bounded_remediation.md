# SKILL-142 Subtask 5 — Inline as a bounded light tier; remediation pass bounded to its delta

Parent: `.feature-specs/SKILL-142-bounded-loop-convergence/spec.md` (unit 4)

## Scope

Give review two honest depth tiers, bound the reserved remediation pass to the
remediation delta, and terminate that pass on evidence rather than on budget.

`inline` is specified today as a topology variant of delegated — same coverage,
one worker. Measured behavior disagrees: on the SKILL-141 run, pass two ran
inline and reported reviewing "by reading only", naming no lanes and launching no
specialist workers, in 346,677 ms (5.8 min) against delegated pass one's 873,529
ms (14.6 min) across seven lanes. Aggregate telemetry agrees: inline averages 3.0
findings/run against delegated's 7.6, and 0.19 Blockers/run against 0.29.

Inline is already the light tier in practice. The defect is the label, not the
behavior, which is why aligning the contract with observed behavior carries low
behavioral risk.

Independently, the reserved second pass reviews the wrong scope. Both passes
review the complete base-to-current delta, pinned to the immutable
`review_base_sha` and only growing, so pass two re-runs an open-ended search over
everything pass one already searched. `context:feature-remediation` already exists
to bound it and is overridden by `skills/bill-feature-goal/content.md:159`, which
requires "the complete base-to-current delta: committed, staged, unstaged, and
untracked" for every child review.

Termination is budget-shaped, not evidence-shaped: the
`review --changes_requested--> implement_fix` edge carries `perEdgeCap = 1` with
`capExhaustionBehavior = ADVANCE`, and a separate pre-`validate` Blocker gate stops
the run. The operator receives "the budget ran out", not "this finding is still
unresolved, and here is why."

## Prior Work On This Branch

Commit `c6f4b1c7 feat(SKILL-142): define inline review as a bounded light tier`
is already on `feat/SKILL-142-inline-review-depth-tier` and touches
`skills/bill-code-review/content.md`.

Treat this unit as **in progress, not greenfield**. Audit `c6f4b1c7` against the
acceptance criteria below, keep what already satisfies them, and implement only
the remainder. Do not redo or contradict already-landed governed text.

## Evidence Sites

- `skills/bill-code-review/content.md` — the inline contract claiming "the
  complete routed review … regardless of size or risk" and, for the reserved
  pass, "apply every signal-relevant baseline and specialist rubric … required
  coverage".
- `skills/bill-feature-goal/content.md:159` — forces the complete base-to-current
  delta on every child review, overriding `context:feature-remediation`.
- `orchestration/contracts/goal-subtask-review-state-schema.yaml:34,38-47` —
  pass-two-is-inline; `reserved_pass_number` (1..2), `completed_pass_count`
  (0..2), `disposition`.
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/GoalSubtaskReviewStateSchemaPaths.kt:3`
  — `GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION = "0.1"`, bumped by this unit.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt:69`
  — `passNumber in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES`, the two-pass bound.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:369-377`
  — the `review_fix` edge.
- SKILL-141 acceptance criterion 1 — the non-terminal resumable status this
  unit's pause consumes.

## Resolved Decisions

Carried from the parent spec's open questions:

- The bounded remediation pass emits a disposition for **Blocker findings only**.
  This matches the Blocker-only `blocksAdvance` semantics subtask 3 codifies.
  Majors remain in the unaddressed-findings ledger, unverified.
- The operator decision vocabulary at the pause is `retry_fix`,
  `accept_and_advance`, `abandon_subtask`. `retry_fix` grants one fresh
  `implement_fix` iteration each time the operator chooses it, and is
  **operator-granted and unbudgeted** — the human is the bound, so no cap can
  silently multiply across resumes.

## Acceptance Criteria

1. `inline` is redefined as a distinct depth tier, not a topology variant of
   delegated: one agent, no specialist workers, covering the routed areas as an
   explicit checklist at reduced depth, under a bounded budget.
2. The governed text drops "the complete routed review … regardless of size or
   risk" and drops "apply every signal-relevant baseline and specialist rubric …
   required coverage", and states plainly that inline is not equivalent coverage
   to delegated.
3. Inline keeps, unchanged and inherited rather than restated: the severity
   vocabulary, the SKILL-115 admission gate, evidence and consequence
   requirements, the F-XXX risk register format, and telemetry. A lighter tier
   lowers depth and budget, never the bar a finding must clear to be emitted.
4. `delegated` is unchanged and remains the default when no mode is supplied. It
   keeps its proportional routing (2 to 8 lanes observed), its full depth, and
   its loud-fail on unlaunchable native workers with no degradation to inline.
5. `auto` resolves depth from review pass number and nothing else in this change:
   pass one resolves to `delegated`, every later pass resolves to `inline`. The
   rule is expressed as one named, declared rule so a later change can add
   signals (diff size, routed area count, risk markers) without reworking the
   seam.
6. Auto reports its resolved tier and the rule that decided it in review
   metadata, and never resolves silently. Explicit `inline` or `delegated` always
   overrides auto.
7. When `parallel:<agent>` is active, both lanes share the resolved tier. Lane 2
   receives the same tier lane 1 resolved to; a light lane paired with a
   full-depth lane is rejected before either lane starts. Neither lane may
   recursively launch parallel review, unchanged from today.
8. The reserved later review pass runs inline, bounded to the remediation delta
   rather than re-reviewing the full base-to-current delta.
   `context:feature-remediation` stays the bounding mechanism, and
   `skills/bill-feature-goal/content.md:159` is corrected so the goal contract
   stops forcing "the complete base-to-current delta" on every child review.
9. The immutable `review_base_sha` and baseline untracked inventory are unchanged
   and remain the authority for pass one. Only the reserved later pass is
   rescoped.
10. The bounded remediation pass emits an explicit disposition — `resolved`,
    `unresolved`, or `superseded` — for every Blocker the prior pass emitted,
    with evidence citing the specific changed lines that resolve or fail to
    resolve it. Major findings are out of disposition scope.
11. An unevidenced disposition is rejected at the parse seam.
12. The remediation pass's scope is `prior Blocker findings` union
    `diff(pre-fix tree -> post-fix tree)`, so defects introduced by the
    remediation itself are still caught.
13. When every Blocker resolves or is superseded, the child advances to
    `validate`.
14. When any Blocker remains unresolved, the child enters the non-terminal
    resumable status from SKILL-141 — it pauses rather than blocking — persisting
    the unresolved findings, their evidence, the reserved pass state, and the
    resumable step, and surfaces a bounded operator decision. Do not fork a second
    pause mechanism.
15. The operator decision vocabulary is `retry_fix`, `accept_and_advance`,
    `abandon_subtask`. `retry_fix` grants one fresh `implement_fix` iteration per
    operator choice and is unbudgeted; the operator decision is the bound.
16. Resume reuses the persisted review state, `review_base_sha`, and baseline
    untracked inventory exactly, and never re-reserves a consumed pass.
17. `implement_fix` retains `perEdgeCap = 1`, but cap exhaustion is no longer the
    terminating signal: the Blocker disposition is. `capExhaustionBehavior` and
    the pre-`validate` Blocker gate are reconciled so a child cannot both advance
    on cap exhaustion and pause on an unresolved Blocker.
18. The review-state contract version is bumped through the existing
    runtime-contract recipe, with a parity test and a typed
    `Invalid<Contract>SchemaError`. A legacy `0.1` record loud-fails; no silent
    migration.
19. Goal-facing output obeys the bounded-output contract: subtask id, pass,
    per-finding verdict, counts, severity, and class/symbol-or-sanitized-stem
    label only. No path, line number, diff hunk, or raw child output reaches
    `goal_event:`, status, watch, telemetry, or PR surfaces. Location-bearing
    evidence remains retrievable only through
    `skill-bill goal findings --issue-key <KEY>`.
20. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Replacing delegated with a compact prompt, or making the light tier the
  default. Delegated keeps its fan-out and stays the default.
- Removing `mode:inline` or `mode:auto`. Both survive with clarified jobs.
- Lowering the bar a finding must clear. The light tier reduces depth and budget,
  never the admission gate, evidence requirements, or severity vocabulary.
- Reducing specialist coverage, removing a review area, or narrowing routing.
- Changing the immutable per-child review baseline, or advancing it per commit.
- Adding diff-size, area-count, or risk-marker signals to `auto` in this change.
  The seam must accept them later; the rule stays pass-number-only now.
- Emitting dispositions for Major findings.
- Pausing before the single bounded fix attempt runs.
- A general operator pause/resume UX beyond the durable decision surface
  required.
- Retroactively repairing goals already blocked under the two-pass model; a hard
  reset remains the documented recovery.

## Dependency Notes

Depends on SKILL-141, which has landed (merge `e586cb43`). The pause in criterion
14 consumes SKILL-141's non-terminal resumable status.

Depends on subtask 3 (Blocker-only reopen): criterion 17's reconciliation of
`capExhaustionBehavior` with the Blocker disposition assumes Blocker-only reopen
semantics are already governed without contradiction.

Consumes subtask 1's cap-scope declaration for `review_fix` when reconciling
termination behavior.

Prior work `c6f4b1c7` is already on this branch — reconcile, do not restart.

## Validation Strategy

- Tier tests: inline launches no specialist workers and reports reduced
  coverage; delegated is unchanged and remains the no-argument default; auto
  records its resolved tier and the deciding rule; explicit modes override auto.
- Parallel-lane test: both lanes share the resolved tier; a mixed-tier pairing is
  rejected before either lane starts.
- Scope test asserting verification input is `Blocker findings union post-fix
  diff`, rejecting a launch carrying the full base-to-current delta.
- Disposition test: the remediation pass emits one evidenced verdict per prior
  Blocker; an unevidenced verdict is rejected at the parse seam.
- Fix-introduced-defect test: a defect introduced by the remediation itself, in
  the post-fix diff, is caught.
- Pause/resume test: an unresolved Blocker pauses resumably; resume reuses review
  state, `review_base_sha`, baseline untracked inventory, and consumed pass
  count, and never re-reserves a consumed pass.
- Operator-decision test: `retry_fix` grants a fresh `implement_fix` iteration and
  is not silently capped; `accept_and_advance` and `abandon_subtask` terminate as
  declared.
- Termination-reconciliation test: a child cannot both advance on cap exhaustion
  and pause on an unresolved Blocker.
- Round-trip test proving verification verdicts and evidence persist across
  reload.
- Schema and parity tests for the bumped review-state contract version and pass
  bounds; a legacy `0.1` record is rejected through the typed error.
- Rejection test: a light-tier pass claiming delegated equivalence.
- Output test asserting no path or line number reaches goal-facing surfaces.
- Focused Gradle tests for changed modules, then the full repository gates.

## Next Path

Final subtask. On completion, the feature is ready for PR.
