# SKILL-234 · Subtask 1: portable goal recovery

## Scope

Add a versioned portable review-baseline artifact to goal child state,
persist it before implementation, and use it to recover after local runtime
state is unavailable. Rehydrate only when the artifact and repository prove
that the child has not progressed beyond a safe reconstruction point. Add an
explicit orphan replacement path for children stopped at `create_branch`,
plus typed blocked reasons and durable audit records for all recovery outcomes.

## Acceptance Criteria

1. The portable artifact stores the child workflow identity, repository
   identity, goal branch, review base SHA, baseline untracked paths, schema
   version, and integrity digest.
2. The artifact is written atomically before the child can enter
   implementation and is available through the decomposition manifest or an
   equivalent repository-portable state path.
3. Resume rehydrates missing local review state from a valid artifact when the
   child has no implementation attempt, commit, or conflicting branch evidence.
4. A `create_branch` child with no durable execution evidence can be recovered
   through an explicit operator action that retires the stale identity,
   captures a new baseline, and atomically updates the manifest.
5. Missing or invalid provenance blocks with a typed reason and an actionable
   recovery instruction; no branch-wide baseline is captured implicitly.
6. Recovery audit state records the source workflow identity, replacement
   identity, artifact digest, selected base, and recovery reason.
7. Existing unreachable-review-base recovery uses the shared artifact
   validation and audit path without changing its accepted behavior.
8. Tests cover valid rehydration, safe orphan replacement, missing artifact,
   malformed artifact, digest mismatch, repository mismatch, branch mismatch,
   unsafe untracked paths, implementation evidence, and audit persistence.
9. `runtime-kotlin/gradlew check`, `skill-bill validate`, and `./install.sh`
   pass with no new suppression, exemption, or baseline entry.

## Non-Goals

- Recovering a child after implementation evidence without portable provenance.
- Inferring baseline untracked paths from the current worktree.
- Changing review scope, Git branch policy, or completed-subtask commits.
- Adding Linear integration.

## Dependency Notes

This subtask is independent of the blocked SKILL-233 architecture goal. It
touches goal orchestration, manifest contracts, Git review operations,
persistence, CLI recovery, and their tests as one behavior-preserving
recovery boundary.

## Validation Strategy

Use store fixtures that omit the original review state while retaining the
portable artifact, and repository fixtures that vary branch, reachability,
working-tree paths, and implementation evidence. Assert observable resume,
blocked, replacement, and audit outcomes rather than internal collaborator
calls.

## Next Path

```bash
skill-bill goal SKILL-234
```
