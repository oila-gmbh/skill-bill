# SKILL-202 — One review pass, verified findings, one fix round

## Intended Outcome

A subtask child reviews its delta exactly once. Every finding that pass produces
is then verified against the subtask's declared intent and against the boundary
memory of the module the finding touches. Findings that survive verification are
fixed in exactly one remediation round, at any severity including Minor and Nit.
The run then advances to validate. Review never runs a second time, and no
finding severity can reopen it.

## Current Behaviour

`FeatureTaskRuntimePhaseWorkflowDefinition` declares the pipeline
`preplan, plan, implement, audit, plan_fix, implement_fix, review, validate,
write_history, commit_push, pr`, with `plan_fix` and `implement_fix` loop-only so
a clean run skips them. One backward edge drives review remediation:

- `review --changes_requested--> plan_fix`, `loopId = review_fix`,
  `perEdgeCap = null`, `warnAfterIterations = 3`
  (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:674`).
  Uncapped by design: the triggering verdict is the only exit.
- `FeatureTaskRuntimeReviewVerdict.verdict` derives `changes_requested` from
  `severity.requiresRemediation`, and `GoalSubtaskReviewState.blocksAdvance`
  (`.../model/GoalSubtaskReviewState.kt:136`) treats `blocker` and `major` as
  advance-blocking. So severity, not correctness, is the re-entry signal.
- Because `implement_fix` sits immediately before `review` in `stepIds`, the
  round's forward edge lands back on `review`, and the whole
  `review_base_sha`-to-worktree delta is reviewed again.

## Verified Root Cause

Traced in the code and in a real run; do not re-derive.

The loop re-enters on a finding's severity rather than on whether the finding is
right, and each remediation round is new code that the next pass reviews. A
remediation can therefore mint the findings that justify the next round. Rounds
multiply on the reviewer's own churn, and every round pays for a full re-review
of the entire delta.

Observed on capmo-android WE-4863 subtask 2. Pass one objected to test
placement. The remediation satisfied it by deleting a ViewModel-level assertion
and re-homing the coverage at the screen level. Pass two then raised three
findings, all located in the files that remediation had just authored, including
a Major for the deleted assertion. Three `review_fix` iterations went to
test placement while the two production lines stayed untouched from the first
commit, and the run ended at a provider spend limit.

Two pieces of the wanted design already exist and must be reused rather than
rebuilt:

- `SpecIntentProjectionExtractor`
  (`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/SpecIntentProjectionExtractor.kt`)
  already extracts a budgeted intent projection from a governed spec.
- `GoalPlanningContext`
  (`runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/model/GoalPlanningContext.kt:26`)
  already models titles-only boundary discovery: a catalog of
  `## [<date>] <title>` headings with stable ids and source paths, caps of 32
  files, 64 headings per file, and 256 headings total, with bodies resolved on
  demand for selected ids only under `MAX_SELECTED_BODIES = 24`,
  `MAX_BODY_BYTES = 8192`, `MAX_TOTAL_BODY_BYTES = 65536`. Discovery parses each
  file inside the runtime; unselected bodies never reach a prompt.

## Target Topology

```
preplan -> plan -> implement -> audit -> review -> verify_findings -> [implement_fix] -> validate -> write_history -> commit_push -> pr
```

- `review` has no backward edge. Its verdict is recorded and never routes.
- `verify_findings` is a new forward phase settling `findings_verified` when at
  least one finding survived, else `no_findings_verified`.
- One declared edge `verify_findings --findings_verified--> implement_fix` with
  `perEdgeCap = 1` and `capExhaustionBehavior = ADVANCE`, so the fix round runs
  once and the run advances whatever its outcome.
- `implement_fix` stays loop-only and moves to sit immediately before `validate`,
  so its forward edge lands on `validate` rather than on `review`.
- A phase entry gate makes the rule structural: `implement_fix` is unreachable
  unless `verify_findings` settled `findings_verified`.
- `plan_fix` is removed. Its only caller was the `review_fix` edge.

## Acceptance Criteria

1. A subtask runs the `review` phase exactly once. No verdict, severity, or
   finding count can re-enter it, and no code path re-reviews a remediation
   delta.
2. Every finding from that pass is verified individually against the subtask's
   spec intent projection and against boundary memory scoped to the finding's
   path, yielding a verified-or-rejected disposition plus a bounded reason.
3. A verified finding is fixed regardless of severity. Minor and Nit findings are
   fixed in the same round as Blocker and Major.
4. A rejected finding is never fixed and is recorded in the unaddressed-findings
   ledger with its rejection reason, retrievable through
   `skill-bill goal findings --issue-key <KEY>`.
5. The remediation round runs at most once per subtask, driven by a declared edge
   with `perEdgeCap = 1` and `ADVANCE` exhaustion behaviour.
6. The run advances to `validate` after the fix round regardless of whether the
   fix resolved every verified finding. No severity blocks advancement and no
   unresolved-finding pause remains on this path.
7. `implement_fix` is unreachable unless `verify_findings` settled
   `findings_verified`, enforced by a declared phase entry gate rather than by a
   branch in the run loop.
8. Verification reads boundary memory by title first: it receives a catalog of
   headings with stable ids scoped to the boundaries that own the finding paths,
   selects ids semantically, and receives only the selected bodies under
   verification-specific caps tighter than the planning caps.
9. No verification path delivers a whole `history.md` or `decisions.md` to a
   prompt, and no path widens discovery to boundaries that own none of the
   finding paths.
10. The `audit_gap` loop and the `record_rejected` regeneration edges keep their
    current behaviour and caps.
11. Durable state written before this change stays decodable, and a record whose
    shape this change invalidates loud-fails with a named error rather than being
    coerced.
12. Resume lands correctly when a run is interrupted inside `verify_findings` or
    inside the single `implement_fix` round, without minting a second round.
13. Every prompt-visible and operator-visible surface describes the new flow. No
    surface still claims that remediation continues while findings survive, that
    Blocker and Major reopen `implement_fix`, or that Minor and Nit merely
    advance.
14. The repository validation gate passes.

## Constraints

- Topology is declaration data. Express the change in
  `FeatureTaskRuntimeTransitionDeclaration` (`forwardPhaseIds`, `backwardEdges`,
  `entryGates`, `loopOnlyPhaseIds`, `loopOnlySuccessors`), not as phase-identity
  branches in `FeatureTaskRuntimeTransitionFunction` or the run loop.
- Reuse `SpecIntentProjectionExtractor` for intent. Do not add a second spec
  reader.
- Reuse the `GoalPlanningContext` heading-catalog and body-resolver machinery for
  boundary memory. Scope it by path and tighten its caps; do not fork a parallel
  discovery implementation and do not relax `MAX_BOUNDARY_FILE_BYTES`.
- Verification is one child phase settling once per subtask. It never loops, and
  it never edits the worktree.
- Severity keeps its reporting role in findings and the ledger. It must stop
  being control flow.
- Parallel review stays two full lanes counting as the single pass. Neither lane
  may recursively launch review.
- Contract and schema changes follow the existing versioning discipline: bump
  what the durable contract requires and loud-fail an incompatible record.
- Validation runs through the runtime-owned gate declared in
  `.skill-bill/config.yaml` (`validation_gate.gradle_wrapper:
  runtime-kotlin/gradlew`). Read the gate's findings rather than trusting a
  wrapper exit code.
- Branch prefix `feat/`. No AI co-author footers.

## Accepted Trade-off

With no re-review, a verified finding whose single fix round fails to resolve it
reaches `validate` unfixed. That is deliberate: the ledger records it, the
validation gate still runs, and the cost of a guaranteed second full review is
the problem this change exists to remove. Do not reintroduce a re-review to close
this gap.

## Non-Goals

- Changing the `audit_gap` loop, the `record_rejected` quarantine edges, or the
  audit-first ordering.
- Changing planning's own boundary discovery, its caps, or its allowlist.
- Adding a second remediation round, a conditional re-review, or an operator
  decision gate on unresolved findings.
- Teaching verification to consult git history, PR review comments, or other
  subtasks' ledgers.
- Changing review lane assembly, evidence brokering, or the review packet
  contract.
- Cross-subtask or cross-goal learning from rejection reasons.

## Subtasks

1. `spec_subtask_1_single-review-pass-one-fix-round.md` — collapse the review
   remediation loop to one pass and one bounded fix round.
2. `spec_subtask_2_verify-findings-against-intent.md` — add the
   `verify_findings` phase gating the fix round on intent verification.
3. `spec_subtask_3_scoped-boundary-memory-for-verification.md` — give
   verification path-scoped, titles-first boundary memory under tightened caps.
4. `spec_subtask_4_surface-verification-dispositions.md` — make the ledger, CLI,
   status, telemetry, and skill prose describe the new flow.
