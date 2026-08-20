# SKILL-202 · Subtask 2 — Verify findings against the subtask's intent

Parent: `spec.md`

## Scope

Add the `verify_findings` phase between `review` and the fix round. It judges
each finding from the single review pass against the subtask's declared intent
and emits a per-finding disposition. Only verified findings reach
`implement_fix`. Boundary memory arrives in subtask 3; this subtask verifies
against spec intent alone.

Files in play:

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt`
  — `PHASE_VERIFY_FINDINGS`, `stepIds`, `stepLabels`, `requiredArtifactsByStep`,
  the edge source move, and the new entry gate.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimeVerdict.kt`
  — `FINDINGS_VERIFIED` and `NO_FINDINGS_VERIFIED`.
- a new verification verdict model beside
  `.../model/FeatureTaskRuntimeReviewVerdict.kt` carrying the ordered
  dispositions.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/SpecIntentProjectionExtractor.kt`
  — reused as-is for the intent projection.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptComposer.kt`,
  `.../FeatureTaskRuntimePhasePromptDirectives.kt`, and
  `.../FeatureTaskRuntimePhaseProjectionShapes.kt` — the phase's prompt, its
  directives, and its projection shape.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt`
  — launching the phase and persisting its output.

## Preferred Approach

Declare `verify_findings` as a forward phase after `review`, consuming the review
phase's durable finding list plus the spec intent projection. Move the fix edge's
source from `review` to `verify_findings`, triggered by `FINDINGS_VERIFIED`,
keeping `perEdgeCap = 1` and `ADVANCE`. Add a `FeatureTaskRuntimePhaseEntryGate`
making `implement_fix` unreachable unless `verify_findings` settled
`FINDINGS_VERIFIED`, so "no fix without verification" is topology rather than a
run-loop branch.

One disposition per finding, in the review pass's order: the finding's identity,
`verified` or `rejected`, and a bounded reason. Verification never edits the
worktree and never re-runs review. It settles `FINDINGS_VERIFIED` when at least
one disposition is `verified`, else `NO_FINDINGS_VERIFIED`.

The verification prompt asks one question per finding: does acting on this
finding serve the subtask's declared intent, or does it contradict a decision the
spec already made? A finding that objects to something the spec explicitly
requires, or that argues for work the spec lists as a non-goal, is rejected with
that reason. The fix round receives only the verified subset.

## Acceptance Criteria

1. `verify_findings` runs once per subtask, after `review`, and never mutates the
   worktree.
2. It emits exactly one disposition per finding from the review pass, in the same
   order, each carrying `verified` or `rejected` and a bounded reason.
3. A finding that contradicts the subtask spec's declared intent, requirements,
   or non-goals is rejected, and its reason names what it contradicts.
4. `implement_fix` receives only verified findings, and receives them regardless
   of severity.
5. `implement_fix` is unreachable when no finding is verified, enforced by the
   declared entry gate, and the run advances to `validate` instead.
6. Rejected dispositions are persisted durably with their reasons for subtask 4
   to surface.
7. A review pass with no findings settles `verify_findings` as
   `NO_FINDINGS_VERIFIED` without launching a verification agent turn.
8. Resume inside `verify_findings` reuses the persisted review finding list and
   does not re-run review.
9. The phase's projection is budgeted before launch like every other phase
   projection, and an over-budget or malformed record loud-fails.
10. The repository validation gate passes.

## Non-Goals

- Reading `history.md` or `decisions.md`, which is subtask 3.
- Surfacing dispositions through the ledger or CLI, which is subtask 4.
- Letting verification propose its own findings or edit code.
- Any second review pass.

## Dependencies

Subtask 1. The single-pass topology and the capped fix edge must exist first.

## Validation Strategy

Unit tests over the verdict derivation and the entry gate: all rejected, all
verified, mixed, and empty finding lists. A prompt-composer test asserting the
phase receives the review findings and the intent projection and nothing else.
Run the runtime-owned validation gate.

## Next Path

`spec_subtask_3_scoped-boundary-memory-for-verification.md`
