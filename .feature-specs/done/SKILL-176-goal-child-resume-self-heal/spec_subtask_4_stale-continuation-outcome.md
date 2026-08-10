# SKILL-176 · Subtask 4 — Blocked-reason fidelity

## Scope

Make the reason a stopped goal child reports describe the failure that actually stopped it. Two independent mechanisms currently break this, and both must be fixed together — fixing either alone leaves the operator reading a sentence that may be unrelated to the fault.

**Mechanism A — a stale outcome outranks the present.** A `goal_continuation_outcome` keeps authority after its recorded cause no longer holds.

**Mechanism B — the block seam discards its own reason.** `blockedGoalReviewRun` computes a specific reason, persists it through `blockAndPersist`, then returns a bare `GoalReviewRunPreparation.Blocked`; the caller ignores it and blocks with a fixed string.

Primary sites:

- `runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerWorkflowStores.kt` — `terminalOutcomeFor` (line 2434) and its short-circuit at lines 2440-2449; `goalContinuationOutcome` (line 2505); `goalContinuationTerminalStatus` (line 2575); `resolveTerminalOutcome` (line 1735); `crashReconcileToResumable` (line 1759); `reconcileAuthoritativeOutcomes` (line 1548).
- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt` — `blockedGoalReviewRun` (line 2024) and the caller at line 1786 that replaces its reason with a fixed sentence.

`terminalOutcomeFor` consults the stored artifact first and returns immediately on any recognized status. There is exactly one staleness exception today — a `COMPLETE` outcome carrying no commit sha falls through so the measure branch can heal it (line 2449). Every other status, including `blocked`, is taken as final regardless of age or of whether its cause survives.

The consequence is that a resume reports history rather than state. On SKILL-15 subtask 3 the stored reason described owned paths "already staged outside this workflow" and instructed the operator to run `git restore --staged`. The index was empty, the worktree was clean, the work was already committed at `72584aa` — and the code that produced that sentence had been deleted in commit `a6e756ee` and was not present in the running build. Because the artifact short-circuits, no later observation could displace it, and the resume reproduced the same text on every attempt until the row was edited by hand.

`crashReconcileToResumable` exists precisely to release a stranded child, but it is only reached when `resolveTerminalOutcome` returns null — which a stored `blocked` outcome prevents.

Mechanism B is what produced the same symptom after the stored outcome was removed. At 2026-08-09T07:44:07Z the supervisor recorded `liveness_class: block` with "Goal-subtask review preparation could not establish the exact durable review scope." while `goal_continuation_outcome` was absent — nothing was being replayed. Review preparation had succeeded; the child crashed retaining producer-output evidence for `review:0:2` (subtask 6). Because every path inside the review-preparation envelope funnels through `GoalReviewRunPreparation.Blocked`, and the caller substitutes one fixed sentence, an evidence-store crash is indistinguishable from a genuine scope failure at the operator boundary.

The specific reason is not lost — `blockAndPersist` already writes it. It is discarded on the way out.

## Acceptance Criteria

1. A stored non-complete `goal_continuation_outcome` is not authoritative on its own; a resume establishes whether its recorded cause still holds before reporting it.
2. A stored `blocked` outcome whose cause no longer holds stops short-circuiting, so `resolveTerminalOutcome` falls through to crash reconciliation or to a freshly derived status.
3. A stored `blocked` outcome whose cause does still hold is reported unchanged, with its existing reason text intact.
4. A resume never surfaces remediation instructions attributable to a code path absent from the running build.
5. A blocked outcome that is displaced as stale leaves durable evidence of the displacement, recording the original reason, so a wedge that recurs is diagnosable rather than silently overwritten.
6. The existing `COMPLETE`-without-sha staleness exception at line 2449 keeps its current behavior, and the new mechanism composes with it rather than replacing it.
7. `reconcileAuthoritativeOutcomes` does not re-block a child that staleness detection just released, and the two passes converge rather than fighting across resumes.
8. A regression test seeds a child row carrying a `blocked` outcome whose cause is provably gone, resumes, and asserts the run proceeds instead of replaying the stored reason; it fails against the pre-fix runtime.
9. The specific reason computed by `blockedGoalReviewRun` reaches the operator and the observability event; no caller replaces it with a generic sentence describing a different failure class.
10. A review-preparation failure that genuinely concerns review scope still reports that, so removing the fixed string does not cost the operator information they have today.
11. Every other terminal that substitutes a fixed reason for a computed one is audited across the review and phase-outcome seams, and each remaining substitution either carries the specific reason or states why none exists at that point.
12. A regression test fails the child inside the review-preparation envelope for a cause unrelated to review scope, and asserts the surfaced reason names that cause; it fails against the pre-fix runtime.

## Non-Goals

- Rewriting or migrating historical `goal_continuation_outcome` rows. Detection happens at read time.
- Broadening what counts as a terminal status, or changing the `goalContinuationTerminalStatus` wire vocabulary.
- Making the outcome artifact optional or removing it as the primary handoff record.

## Dependencies

None. Independently landable.

## Validation Strategy

- Seed the wedge from a durable artifacts JSON shaped like child `wftr-20260808-175505-c5po`, including a reason string that no current code path can emit.
- Cover both directions: cause gone, released; cause standing, reported unchanged. A fix that always releases is a regression, not a fix.
- Assert that the displacement evidence is durable and names the original reason.
- Exercise resume twice in sequence to prove convergence against `reconcileAuthoritativeOutcomes` rather than an alternating state.
- For mechanism B, assert on the observability event's `activity_summary` and not only on the internally persisted reason, since the durable write was already correct — the defect is what escapes to the operator.
- Inject a non-scope failure inside the preparation envelope, since a scope-shaped failure would pass even against the pre-fix runtime.

## Next Path

Subtask 5 — the operator repair path.
