# SKILL-135 Subtask 2: Delegated-Then-Inline Review Pass Sequence

## Scope

Replace code-review mode immutability with an immutable two-pass sequence in which pass one runs the selected delegated mode and pass two is forced inline, while preserving review scope, blocker disposition, and crash-safe pass accounting.

## Acceptance Criteria

1. Review runs at most two passes per subtask, with pass one executing in the selected delegated mode and pass two forced inline regardless of the caller's original selection.
2. The pass sequence is durable and immutable: a resume reuses the recorded sequence and pass position rather than re-deriving it from the current branch, caller arguments, or a sibling subtask.
3. An explicit attempt to change the mode of an already-started sequence fails loudly before a child is launched, and a rejected resume leaves durable parent and child review policy unchanged.
4. Durable review state expresses the sequence through reserved, completed, and emitted pass accounting, and a crash that leaves a reserved pass without completed durable output resumes that reserved pass rather than allocating another.
5. Review is scoped to the subtask's complete delta from its immutable base, including committed, staged, unstaged, and child-owned untracked paths, with per-subtask baseline capture semantics unchanged.
6. Blocker findings unresolved after pass two block the subtask loudly with full durable evidence and a compact path-free goal-facing summary.
7. Non-blocker findings never prevent advancement; the subtask records them durably and proceeds to validation.
8. Standalone feature-task runs and goal-child runs share the same pass sequence, scope, and disposition behavior.
9. Contract, domain, and application tests cover sequence ordering, immutability, rejection of mid-sequence mode changes, reserved-pass resumption, and both disposition paths.

## Non-Goals

- Introducing a third review pass or removing the two-pass cap.
- Changing the review severity taxonomy or how findings are classified.
- Changing per-subtask review baseline capture.
- Building the goal-wide findings retrieval surface.

## Dependency Notes

Depends on subtask 1: the pass sequence and its disposition semantics are defined against the reordered phase graph in which review is terminal and never re-enters audit.

## Validation Strategy

- Pass-sequence tests for delegated-then-inline ordering and durable immutability.
- Resume tests covering reserved-pass resumption and rejection of incompatible explicit modes.
- Disposition tests for blocker-blocks-with-evidence and non-blocker-advances.
- Standalone and goal-child parity tests.
- Focused Gradle tests during implementation.

## Next Path

Continue with subtask 3 after this subtask commits.
