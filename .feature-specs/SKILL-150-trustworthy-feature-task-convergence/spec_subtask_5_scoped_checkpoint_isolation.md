# SKILL-150 Subtask 5: Repository-Wide Checkpoint Convergence

## Scope

Make every checkpoint capture the complete current repository state. Path ownership remains diagnostic evidence only and never authorizes, rejects, or narrows implementation, repair, review, validation, history, or commit behavior.

## Acceptance Criteria

1. Checkpoint creation stages every current tracked, staged, unstaged, deleted, renamed, and untracked repository path.
2. No durable owned-path inventory acts as an allowlist for a mutating phase or checkpoint.
3. A path introduced outside the prior inventory is incorporated into the next checkpoint without a policy block or operator decision.
4. Governed `.feature-specs/` paths receive no special exclusion from repository-wide checkpointing.
5. The checkpoint uses the current working-tree content for every changed path and may include pre-existing repository changes.
6. Staging or commit failure remains a technical failure and must not leave a partial checkpoint commit.
7. Checkpoint identity records the branch, phase, loop and generation, parent SHA, repository-delta digest, included paths, and resulting commit SHA in durable state.
8. Checkpoint commit messages identify the phase, loop, and generation sufficiently to distinguish initial implementation, audit repair, and review remediation history.
9. Review input is constructed from the resulting repository-wide checkpoint.
10. Tests prove that paths added during later audit or review repair are checkpointed without ownership expansion or authorization blockers.

## Non-Goals

- Rewriting existing checkpoint history.
- Replacing Git with a custom version-control store.
- Adding a path-authorization or operator-approval workflow.

## Dependency Notes

Depends on Subtask 1 so checkpoint identities and owned-path provenance survive crash and resume.

## Validation Strategy

- Test clean and dirty repositories with staged, unstaged, untracked, deleted, renamed, and intent-to-add paths.
- Test paths first discovered during audit and review repair, symlink and case-normalization behavior, commit failure, staging failure, and crash recovery.
- Verify checkpoint trees contain the complete repository delta.
- Exercise audit and review backward edges, standalone work, and goal-child work.
- Build a concurrent-spec regression using two issue keys and assert the checkpoint includes both repositories' current paths without blocking.

## Next Path

Continue with Subtask 6 to route oversized work and deterministic quality failures before shallow review cycles begin.
