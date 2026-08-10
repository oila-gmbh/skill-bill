# SKILL-176 — Goal-child resume self-heal

Status: Pending

## Intended Outcome

A goal child that stops mid-run resumes without an operator editing SQLite by hand.

Six defects currently stop a goal child from resuming. Each was hit on goal SKILL-15 subtask 3 (child `wftr-20260808-175505-c5po`) between 2026-08-08 and 2026-08-09, and each required either a raw `UPDATE` against `review-metrics.db` or an incidental state change to clear.

The shared root pattern is that the runtime treats a *durable record* as authoritative without asking whether the record is still true. A field absent because it predates the contract is read as an explicit choice; a commit sha recorded when it was HEAD is used after it stopped being reachable; a blocked outcome persists after its cause is gone; an evidence key already claimed by one producer is written again by another.

A second pattern compounds the first: when the runtime does stop, it frequently reports the wrong reason. Two independent mechanisms produce this — a stale stored outcome replaying a dead cause, and a block seam that discards the specific reason it just computed. The operator debugging a wedge is therefore reading a sentence with no reliable relationship to what failed.

## Background — the four defects

### 1. Validation-depth conflict gate conflates "absent" with "default"

`FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap` decodes a missing `validation_depth` to `ValidationDepth.DEFAULT`:

```kotlin
private fun Map<String, Any?>.optionalGoalContinuationValidationDepth(): ValidationDepth {
  val raw = optionalStringField("validation_depth") ?: return ValidationDepth.DEFAULT
  ...
}
```

`ValidationDepth.DEFAULT` is `FULL`. The goal launcher stamps `build_only` on every non-final subtask, so `suppliedGoalContinuationConflict` compares `BUILD_ONLY` against a durable `FULL` that was never chosen and hard-blocks:

> The supplied goal-continuation validation depth conflicts with its durable child policy.

Note the asymmetry inside that same gate: `codeReviewMode` and `parallelReviewAgent` guard the *supplied* side with `?.takeIf`, tolerating absence there. Only the durable side conflates absent with default, and `validationDepth` is the one field non-nullable on both sides.

Every goal child row written before `validation_depth` joined the continuation contract is a permanent resume landmine.

### 2. An unreachable remediation base cannot be recovered

`goal_subtask_review_state.remediation_base_sha` held `73993c8`: a real commit object, on no branch, not an ancestor of HEAD `9d814e8`. Both are sibling remediation checkpoints off the same parent `173fb03`.

Review preparation classified this correctly — `BASE_NOT_ANCESTOR` is already in `recoverableReviewBaseFailures` — but recovery was refused by its own gate:

```kotlin
private fun GoalSubtaskReviewState.canRecoverReviewBase(): Boolean = completedPassCount == 0 &&
  passResults.isEmpty() &&
  emittedPassCount == 0 &&
  reviewInputArtifact == null &&
  disposition == GoalSubtaskReviewDisposition.PENDING
```

The wedged child had two completed passes, so recovery was categorically unavailable. Worse, `recoverGoalReviewInput` rebuilds only from `state.reviewBaseSha` — it never repoints `remediationBaseSha`, so a bad remediation base is unrecoverable by construction even when the gate allows recovery.

The subtask blocked with "Goal-subtask review preparation could not establish the exact durable review scope." and stayed blocked.

### 3. Sibling remediation checkpoints strand a recorded base

`recordRemediationBaseSha` stamps HEAD immediately after the checkpoint commit, which is correct at the instant it runs. Something then moved the branch ref back to `173fb03` and committed a second remediation checkpoint, orphaning the first. Defect 2 is the recovery gap; this is the upstream cause that produces the unreachable sha in the first place.

### 4. A stale blocked outcome stays authoritative forever

`terminalOutcomeFor` reads `goal_continuation_outcome` first and short-circuits on any recognized status. A stored `blocked` outcome therefore outranks every later observation of the world.

On SKILL-15 this replayed a checkpoint-guard message about paths "already staged outside this workflow" against a clean index and an empty worktree — and the string that produced it no longer exists in the runtime at all, having been replaced by adoption in commit `a6e756ee`. The operator was told to unstage paths that were not staged, by a code path that had been deleted.

### 5. Producer-evidence identity collides across attempts and agents

`producer_output_evidence` is keyed `(workflow_id, phase_id, generation, attempt)`. `agent_id` and `model` are recorded in the row but are not part of the identity. `SqliteRejectedOutputDiagnosticRepository.retainProducerOutput` writes with `INSERT OR IGNORE`, then reads back and compares sha, byte size, and payload, throwing `RejectedOutputDiagnosticError.Conflict` on any difference.

A re-entered attempt number that produces different output therefore neither overwrites nor dedupes — it hard-fails. On SKILL-15 the key `review:0:2` was already held by a claude pass from 2026-08-08T18:49:48Z (14382 bytes, sha `8a5dfb56fd3d`). A later relaunch under cursor re-entered review at the same attempt number, produced different bytes, and crashed the child while storing evidence — after review preparation had succeeded.

Resuming a killed attempt without advancing the counter makes this collision reachable; switching agent mid-run makes it certain, because differing output for one key is exactly what a different producer generates.

### 6. The block seam discards the reason it computed

`blockedGoalReviewRun` derives a specific reason and persists it through `blockAndPersist`, then returns a bare `GoalReviewRunPreparation.Blocked`. Its caller at `FeatureTaskRuntimeRunLoop.kt:1786` ignores that reason and blocks with a fixed string:

> Goal-subtask review preparation could not establish the exact durable review scope.

Every failure inside the review-preparation envelope surfaces as a scope failure regardless of cause. When the defect-5 conflict crashed the child at 2026-08-09T07:44:07Z, the supervisor emitted exactly that sentence — with `goal_continuation_outcome` already removed, so nothing was being replayed. Review preparation had in fact succeeded.

This is mechanically distinct from defect 4. Defect 4 replays a stored reason whose cause is gone; this one overwrites a live, correct reason with a generic one at the moment of failure.

### Operator recovery

All four needed hand-written SQL. `skill-bill goal` already has `reset`, `replan`, and `accept` subcommands; it has no supported path to repair a wedged child, so the only recourse is editing durable state directly with no validation, no backup, and no audit trail.

## Acceptance Criteria

1. A goal child whose durable continuation artifact lacks `validation_depth` resumes and adopts the launcher-supplied depth instead of blocking on a policy conflict.
2. An explicitly-recorded validation depth that genuinely differs from the supplied one still blocks, with its existing message unchanged.
3. Every other field compared in `suppliedGoalContinuationConflict` is audited for the same absent-vs-default conflation, and each one either distinguishes absent from set or carries a stated reason why absence is impossible for it.
4. A stored review or remediation base that is no longer reachable from the goal branch is detected before use and recovered to a reachable ancestor, rather than blocking the subtask.
5. Review-base recovery is available regardless of how many review passes have already completed, and can repoint `remediation_base_sha` and not only `review_base_sha`.
6. The runtime cannot leave a recorded remediation base unreachable: whatever moves a goal branch off a checkpoint it already recorded either does not do so, or updates the recorded base in the same durable transaction.
7. A `goal_continuation_outcome` whose recorded cause no longer holds stops being authoritative, so a resume reports the current state of the run rather than replaying a stale reason.
8. A stale-outcome resume never surfaces remediation instructions derived from a code path that no longer exists in the running build.
9. Retaining producer-output evidence for a phase attempt that already holds different immutable output does not crash the run; the evidence identity distinguishes the producers, or the collision resolves to a defined outcome.
10. A goal child that stops reports the reason it actually stopped for: the specific reason computed at the failure site reaches the operator, and is never replaced by a generic one describing a different failure class.
11. `skill-bill goal` exposes a supported repair path that clears each wedge class above on an existing durable row, without raw SQL, refusing to act when the row is not actually wedged.
12. Each of the six defects has a regression test that fails against the pre-fix runtime and reproduces from a durable row shaped like the SKILL-15 one, not only from a synthetic in-memory state.
13. Existing goal children already wedged in a local database are recoverable by the new paths without losing completed subtask work or review pass history.

## Constraints

- Durable-state schema changes must leave existing rows readable. A row written by the current runtime keeps resuming after the change.
- The distinction between absent and explicitly-set must survive a write/read round trip through `toArtifactMap` / `fromArtifactMap`; `rejectUnknownGoalContinuationKeys` means new keys need deliberate registration.
- Recovery widens what the runtime heals silently. Every silent heal emits durable evidence of what it changed and why, so a repointed base is never invisible.
- The conflict gate exists to stop a resume from changing a child's policy mid-run. Fixing absent-vs-default must not weaken that: a genuine policy change still blocks.

## Non-Goals

- Redesigning the review pass, remediation loop, or checkpoint model.
- Changing the checkpoint adoption behavior introduced in `a6e756ee`.
- Backfilling or migrating historical `goal_continuation_outcome` rows in place. Detection at read time is the mechanism; a migration is not.
- Any change to prose-mode workflows.

## Subtasks

1. Validation-depth absent-vs-default, plus a full audit of the conflict gate.
2. Review-base reachability detection and recovery, including the remediation base.
3. Orphaned-checkpoint upstream cause.
4. Blocked-reason fidelity — stale outcomes and discarded reasons.
5. `skill-bill goal repair` operator path.
6. Producer-evidence identity across attempts and agents.

## Next Path

```bash
skill-bill goal SKILL-176
```
