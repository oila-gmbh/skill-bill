# SKILL-190 Subtask 1 — Amend and checkpoint-ref git primitives

## Intended Outcome

`WorkflowGitOperations` gains the two capabilities the rest of this bundle needs and does not have
today: amending a runtime-owned commit, and writing, reading, listing, and deleting refs under a
runtime-private namespace. This subtask adds the primitives and their adapter implementation only.
No caller changes behaviour yet.

## Scope

- Extend the `WorkflowGitOperations` port (`runtime-ports/.../workflow/WorkflowGitOperations.kt:33-68`)
  with an amend operation that reuses the currently staged index against HEAD, accepts an optional
  replacement message, and returns the resulting sha.
- Add ref operations scoped to a namespace prefix: create or update a ref at a given sha, resolve a
  ref to a sha, list refs under a prefix, and delete a ref.
- Implement all of them in `GitWorkflowGitOperations` (`runtime-infra-fs/.../GitWorkflowGitOperations.kt`)
  through the existing `GitProcessSupport.runGitCommand` seam at `:74-75`. Add no second
  `ProcessBuilder`.
- Amend uses the plumbing that does not re-stage: the operation must not run `git add` of any kind,
  and must fail rather than silently amending an empty index.
- Ref writes use `update-ref` semantics so a partially completed write leaves either the old value or
  the new one.
- Return the same typed result shape the existing operations use; do not introduce a parallel error
  channel.

## Acceptance Criteria

1. `WorkflowGitOperations` declares an amend operation that rewrites HEAD from the current index and
   returns the new sha, and declares ref create-or-update, resolve, list-by-prefix, and delete
   operations scoped to a namespace prefix.
2. `GitWorkflowGitOperations` implements every new operation through the existing
   `GitProcessSupport` invocation seam; no additional `ProcessBuilder` is introduced in this or any
   other file.
3. The amend operation never stages anything itself, and returns a typed failure when the index is
   empty or when HEAD does not exist.
4. The amend operation refuses to run when HEAD is not a commit the runtime owns, determined by the
   ownership signal the caller supplies; ownership policy itself stays with the caller.
5. Ref writes are atomic in the `update-ref` sense: an interrupted write leaves the ref at its
   previous value or its new value, never at a partial one.
6. Ref operations reject any ref name outside the supplied namespace prefix with a typed failure.
7. Ref deletion of a ref that does not exist succeeds idempotently rather than failing.
8. No existing call site changes behaviour in this subtask; `createCommit`, `pushBranch`,
   `resetSoftToCommit`, and `isCommitAncestor` keep their current semantics and callers.
9. No SQLDelight, adapter, or provider type appears in the port signatures.

## Non-Goals

- Wiring any of the new primitives into the run loop, the checkpoint path, or reconciliation. That
  is subtasks 3 and 4.
- Choosing the ref naming scheme's semantic content. This subtask handles arbitrary prefixes;
  subtask 3 fixes the `refs/skill-bill/checkpoints/...` layout.
- Removing or deprecating `resetSoftToCommit`. Subtask 4 redefines its role.
- Touching the legacy `stageAll` operation at `GitWorkflowGitOperations.kt:123`, which has no
  non-test caller.

## Dependency Notes

No dependencies. This is the foundation subtask; subtasks 3, 4, and 5 all consume these primitives.

Coordinate with nothing in flight — the git port has no other active bundle touching it.

## Validation Strategy

Exercise the primitives against real temporary git repositories through the adapter, not through
mocks, because the behaviour under test is git's:

- Amend against a staged index produces a new sha and leaves the tree identical to a
  stage-then-commit sequence.
- Amend with an empty index returns the typed failure rather than creating an empty commit.
- Amend with a replacement message changes the subject and preserves the tree.
- A ref written, resolved, listed, and deleted round-trips; deleting twice succeeds.
- A ref name outside the prefix is rejected.

Then run the module checks for `runtime-ports` and `runtime-infra-fs`.

## Next Path

Subtask 2 versions the checkpoint-identity contract so the ledger can describe amended commits and
their refs.
