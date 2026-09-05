# SKILL-234: cross-machine goal recovery

## Intended Outcome

Goal execution can continue after moving a repository to another machine
without silently widening the review scope or depending on SQLite state that
exists only on the original machine. The immutable review baseline becomes
portable workflow state, and missing local state has a guarded recovery path.

## Acceptance Criteria

1. A goal child records its immutable review baseline in a versioned,
   repository-portable artifact containing the child workflow identity, goal
   branch, review base SHA, and baseline untracked paths before implementation
   starts.
2. The portable baseline is validated for schema version, repository identity,
   branch identity, reachable base, and path safety before it can recreate
   missing local review state.
3. When the local store lacks an existing child review state, the runtime
   automatically rehydrates it from a valid portable baseline only when the
   child has no implementation attempt, commit, or branch evidence that would
   make a newly reconstructed scope ambiguous.
4. A child that is still at `create_branch` with no durable execution evidence
   has an explicit recovery path that can retire the orphaned workflow
   identity, allocate a replacement identity, capture a new baseline, and
   update the decomposition manifest atomically.
5. Missing, malformed, stale, tampered, or ambiguous baseline state produces a
   typed blocked reason with an operator recovery action. The runtime never
   silently captures a branch-wide replacement baseline.
6. Recovery records the original workflow identity, replacement identity when
   applicable, source artifact digest, selected baseline, and reason in the
   durable goal audit trail.
7. Existing recovery for an unreachable review SHA continues to work and shares
   baseline validation and audit semantics with cross-machine recovery.
8. Tests cover same-machine resume, valid cross-machine rehydration, safe
   pre-implementation orphan replacement, missing artifact, malformed artifact,
   branch mismatch, implementation evidence, and tampered untracked paths.
9. `runtime-kotlin/gradlew check`, `skill-bill validate`, and `./install.sh`
   pass with no new suppression, exemption, or baseline entry.

## Constraints

- Preserve the existing immutable review scope contract.
- Do not infer the original untracked path inventory from the current
  worktree.
- Do not reuse a workflow identity when durable execution evidence exists.
- Keep recovery provider-neutral and manifest-driven.
- Keep the existing one-confirmation feature launch contract unchanged.
- Add no comments.

## Non-Goals

- Changing review semantics or widening review scope.
- Recovering arbitrary lost SQLite history.
- Reconstructing a partially implemented child without portable provenance.
- Changing the Git branch or commit policy for completed subtasks.
- Adding remote Linear tracking.

## Validation Strategy

The recovery boundary is tested through the goal runner with a portable
baseline artifact and a store that starts without the original child review
state. Rejection fixtures cover a missing artifact, an invalid digest, a
different repository or branch, an unsafe path, and existing implementation
evidence. The existing unreachable-base recovery tests remain green.

## Delivery Plan

Implement the portable baseline contract, persist it before child
implementation, add guarded rehydration and orphan replacement, then update
the manifest, audit, CLI recovery surface, and tests in one coherent change.
