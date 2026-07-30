# SKILL-151 Subtask 5 - Complete checkpoint capture without path authorization

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Update workflow Git operations, scoped checkpoint models and operations, branch setup, repository baselines, checkpoint identity persistence, and run-loop checkpoint creation. Capture every workflow-produced staged, unstaged, and untracked change, including paths first discovered during implementation, audit repair, review remediation, validation, or history. Remove durable path-allowlist expansion and operator authorization seams. Isolate pre-existing and concurrent unrelated work through durable baseline or content identity, temporary-index isolation, isolated worktree execution, or an equivalent repository-wide mechanism.

## Acceptance Criteria

1. A checkpoint captures the complete workflow-produced repository delta across staged, unstaged, and untracked state.
2. Newly discovered paths require neither durable allowlist expansion nor operator authorization.
3. Workflow commits do not absorb unrelated pre-existing or concurrent dirty-worktree content.
4. Checkpoint identity is append-only, generation-aware, and recoverable after crash or persistence failure.
5. Production checkpoint handling preserves isolation under concurrent dirty-worktree changes in standalone and goal-child execution.
6. Failed checkpoint persistence preserves caller staging and worktree state and recovers to the recorded parent safely.

## Non-Goals

- Squashing or rewriting existing feature branches.
- Path-by-path operator approval.
- Preserving unrelated changes by committing them into workflow checkpoints.
- Adding or modifying tests or test infrastructure.

## Dependency Notes

Depends on: 1, 2

Checkpoint capture depends on stable persisted identity and truthful workflow obligations but remains independent of audit and review implementation.

## Validation Strategy

Run existing focused module checks and repository validation commands. Add or modify no tests or fixtures.

## Next Path

Proceed to adaptive sizing, review depth, and phase-appropriate quality checks.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_5_complete-checkpoint-capture-without-path-authorization.md
