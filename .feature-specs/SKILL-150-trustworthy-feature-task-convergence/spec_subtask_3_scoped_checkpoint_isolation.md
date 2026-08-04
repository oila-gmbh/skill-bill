# SKILL-150 Subtask 3: Scoped Checkpoint Isolation

## Scope

Replace full-worktree checkpoint staging with workflow-owned staging and explicit
foreign-change protection. Checkpoints must capture the active workflow's
authoritative delta without mutating or committing unrelated user or
concurrent-agent work.

## Acceptance Criteria

1. Checkpoint creation stages only the durable workflow-owned path inventory
   resolved for the active subtask and phase; production checkpoint code no
   longer runs repository-wide `git add -A`.
2. Pre-existing foreign staged, unstaged, and untracked paths remain
   byte-for-byte and index-for-index unchanged after a successful checkpoint.
3. A path introduced by the active phase outside its allowed inventory produces
   a typed non-retryable policy block before any commit.
4. A foreign governed `.feature-specs/` path, including a concurrently prepared
   issue, is never staged, committed, reviewed, or attributed to the active
   workflow.
5. Ambiguous overlap between an owned path and a foreign staged or concurrently
   modified path blocks with the exact path and recovery guidance rather than
   overwriting either side.
6. Staging or commit failure restores the pre-checkpoint index state and
   preserves the working tree; partial index mutation cannot leak into a later
   user commit.
7. Checkpoint identity records the branch, phase, loop and generation, parent
   SHA, owned-path digest, and resulting commit SHA in durable state that
   survives crash and resume.
8. Checkpoint commit messages identify the authority boundary and loop
   generation sufficiently to distinguish initial implementation, audit repair,
   and review remediation history.
9. Review input is constructed from the immutable checkpoint plus the same
   owned-path inventory, so unrelated dirt cannot change its semantic delta
   digest.
10. A regression reproduces the concurrent-spec incident (SKILL-149 appearing
    during SKILL-134) and proves the foreign spec remains uncommitted and
    outside review while the active workflow either checkpoints only its own
    files or blocks safely on a real overlap.

## Non-Goals

- Deleting, stashing, resetting, or auto-committing foreign user changes.
- Rewriting existing checkpoint history.
- Replacing Git with a custom version-control store.
- Allowing agents to expand their owned inventory through output claims alone.

## Dependency Notes

Independent of Subtasks 1–2; checkpoint identity persistence is owned here. The
quarry branch's `2f41e027` (workflow-owned path inventory, private Git index,
`WorkflowCheckpointIdentity` with owned-path digest verification, scoped review
input) is the starting material; its review-input seam must be re-fitted to
current `main`'s `buildGoalSubtaskReviewInput` remediation-baseline behavior
rather than the branch's checkpoint-mandatory variant.

## Validation Strategy

- Test clean and dirty repositories with foreign staged, unstaged, untracked,
  deleted, renamed, and intent-to-add paths.
- Test owned and foreign path overlap, symlink and case-normalization behavior,
  commit failure, staging failure, and crash recovery.
- Verify exact index trees before and after checkpoint operations.
- Exercise audit and review backward edges, standalone work, and goal-child
  work.
- Build a concurrent-spec regression using two issue keys and assert each commit
  contains only its own paths.

## Next Path

Terminal subtask: reconcile the decomposition manifest and spec to their final
state after landing.
