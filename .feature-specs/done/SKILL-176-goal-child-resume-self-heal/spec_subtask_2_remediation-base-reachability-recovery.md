# SKILL-176 · Subtask 2 — Review-base reachability and recovery

## Scope

Detect a stored review or remediation base that is no longer reachable from the goal branch, and recover it to a reachable ancestor instead of blocking the subtask permanently.

Primary sites:

- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeGoalContinuationRecorder.kt` — `buildGoalReviewInput` selects `remediationBaseSha` when `reservedPassNumber >= 2` (line 272); `recoverGoalReviewInput` (line 302); `recoverableReviewBaseFailures` (line 498); `canRecoverReviewBase()` (line 509).
- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt` — `buildGoalReviewRun` (line 1972) and the `GoalReviewRunPreparation.Blocked` terminal at line 1786.
- `runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt` — `remediationBaseSha` (line 364) and its encode/decode.

Two independent gaps produce the permanent wedge:

**The recovery gate refuses once any pass exists.** `canRecoverReviewBase()` requires `completedPassCount == 0 && passResults.isEmpty() && emittedPassCount == 0 && reviewInputArtifact == null`. A remediation base only becomes selectable at `reservedPassNumber >= 2`, which by definition means at least one pass already completed — so for the remediation base the gate can never be satisfied. The two conditions are mutually exclusive.

**Recovery repoints the wrong field.** `recoverGoalReviewInput` rebuilds from `GoalSubtaskReviewBaseline(request.state.reviewBaseSha, ...)` (line 306) and never consults or updates `remediationBaseSha`. Even if the gate opened, an unreachable remediation base would survive recovery unchanged.

`BASE_NOT_ANCESTOR` and `BASE_MISSING` are already classified in `recoverableReviewBaseFailures`, so failure detection is correct today; only the response is missing.

Observed on child `wftr-20260808-175505-c5po`: `remediation_base_sha` was `73993c8`, a real commit object on no branch and not an ancestor of HEAD `9d814e8`, with `completedPassCount = 2`. The subtask blocked with "Goal-subtask review preparation could not establish the exact durable review scope." and could not self-heal.

## Acceptance Criteria

1. Before a stored review or remediation base is used to materialize review input, its reachability from the goal branch is established, and an unreachable base is classified rather than surfaced as an opaque preparation failure.
2. An unreachable remediation base is recovered to a reachable ancestor and the recovery is persisted to `remediation_base_sha`, so the next resume reads the repointed value.
3. Review-base recovery is reachable when review passes have already completed, and the mutual exclusion between `canRecoverReviewBase()` and `reservedPassNumber >= 2` is eliminated rather than worked around.
4. Recovery selects a base that preserves the pass's intended scope where one exists — the nearest reachable ancestor of the unreachable sha — and does not silently widen the reviewed delta to the whole branch when a narrower reachable base is available.
5. A recovery that changes a stored base emits durable evidence recording the original sha, the replacement, and why the original was rejected.
6. Recovery that cannot find any reachable base still blocks, with a reason naming the unreachable sha and the goal branch it was measured against.
7. A resume whose bases are all reachable takes no recovery path and produces byte-identical review input to today.
8. A regression test seeds a durable review state whose `remediation_base_sha` points at a commit reachable from no branch, with `completedPassCount` at 2, drives the review phase, and asserts the subtask proceeds; it fails against the pre-fix runtime.
9. `cappedReviewIsStale` in `FeatureTaskRuntimeRunner.kt:258`, which also feeds `remediationBaseSha` into `buildGoalSubtaskReviewInput`, does not regress when a base is unreachable.

## Non-Goals

- Preventing the unreachable sha from being recorded in the first place; that is subtask 3.
- Changing how the remediation base is chosen when the branch is healthy.
- Changing review pass caps, dispositions, or the remediation loop shape.

## Dependencies

None. Independently landable, and deliberately ordered before subtask 3 so the recovery path exists before the upstream cause is closed.

## Validation Strategy

- A git-fixture test constructing the exact SKILL-15 topology: parent commit, two sibling children, branch ref on the second, stored base on the first.
- Unit coverage on the recovery gate for the pass-count combinations that previously excluded remediation recovery.
- Assert the persisted `remediation_base_sha` after recovery, not just that the run proceeded — the durable repoint is the fix.
- A negative test where no reachable ancestor exists, asserting a block carrying the unreachable sha.

## Next Path

Subtask 3 — the upstream cause that orphans the checkpoint.
