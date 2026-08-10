# SKILL-176 · Subtask 3 — Orphaned-checkpoint upstream cause

## Scope

Establish how a recorded remediation base becomes unreachable, and close that path so the runtime cannot strand a sha it already committed to durable state.

Primary sites:

- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt` — `recordRemediationBaseSha` (line 931), which reads HEAD after the checkpoint commit and persists it via `updateReviewState`.
- The checkpoint commit path in `FeatureTaskRuntimeCheckpointScope` / `FeatureTaskRuntimeRunLoop`, and any code that moves, resets, or re-points the goal branch after a checkpoint.

`recordRemediationBaseSha` is correct at the instant it runs: HEAD is the pre-fix tree it intends to review from. The defect is what happens afterward. On the SKILL-15 branch two remediation checkpoints, `73993c8` and `9d814e8`, share the single parent `173fb03`, with the branch ref on the later one. The recorded base was the earlier sibling. Something reset the branch back to `173fb03` and committed again, orphaning a sha the review state already depended on.

The investigation is part of the deliverable: the exact sequence must be identified from the code and reproduced, not inferred. Candidate paths worth eliminating explicitly are a checkpoint retry after a partially failed commit, a crash between the commit and the `updateReviewState` write, a resume that re-runs a phase whose checkpoint already committed, and any rollback that moves the ref without consulting durable review state.

## Acceptance Criteria

1. The sequence that produces two sibling remediation checkpoints off one parent, with the branch ref on the later one, is identified in the code and reproduced by a test.
2. The runtime cannot leave a recorded remediation base unreachable: the identified path either does not move the branch off a recorded checkpoint, or updates the recorded base within the same durable transaction that moves it.
3. Recording the base and committing the checkpoint it names cannot be torn apart by a crash in a way that leaves the review state naming a commit the branch does not contain.
4. A goal child that crashes between the checkpoint commit and the base record resumes with a review state whose base is reachable.
5. A regression test drives the reproduced sequence end to end and asserts the branch and the stored `remediation_base_sha` agree afterward; it fails against the pre-fix runtime.
6. Normal checkpoint and remediation flows produce the same commits and the same recorded base as today.
7. The findings are written to the boundary decision log for the runtime area, so the ordering constraint between committing a checkpoint and recording its sha is durable knowledge rather than a fix comment.

## Non-Goals

- Recovering from an already-orphaned base, which subtask 2 owns.
- Redesigning the checkpoint model, its commit message identity, or the adoption behavior from `a6e756ee`.
- Garbage-collecting or reflog-pruning orphaned commit objects.

## Dependencies

- Subtask 2, non-optional. Recovery lands first so this subtask can prove that a healthy run never needs it, and so a regression here degrades to recovery rather than to a wedge.

## Validation Strategy

- Reproduce the topology from the runtime rather than by hand-crafting git history; a hand-built fixture proves the recovery in subtask 2 but not the cause here.
- Crash-injection between the checkpoint commit and the `updateReviewState` write, asserting the resumed state is coherent.
- Assert on both the git ref and the durable row after each scenario, since the defect is the disagreement between them.

## Next Path

Subtask 4 — stale continuation-outcome detection.
