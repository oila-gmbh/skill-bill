---
status: Pending
---

# SKILL-130 Subtask 1 - Issue execution read model

## Scope

Create the runtime-port and SQLite/application projections that aggregate workflow history into one issue summary and one detailed issue execution model. Define deterministic effective-state, current-attempt, progress, ordering, retry-grouping, diagnostic, and artifact-decoding policies. Preserve provenance to every workflow and repository scope.

## Acceptance Criteria

1. A repository-scoped summary query returns one item per normalized nonblank issue key and separate identities for unassociated workflows.
2. A detail query exposes parent goal state, task structure, attempts, phases/timeline, preplan and plan digests, blocks/failures, retry data, outcomes, and typed diagnostics when available.
3. Aggregation and ordering policies are deterministic and tested for active versus terminal attempts, timestamp ties, contradictory parent/child state, multiple retries, and `SKILL-128`-shaped history.
4. Known artifact shapes are mapped outside UI code; unknown optional artifacts are safely retained for fallback inspection without invalidating valid sections.
5. Reads are repository-scoped, avoid N+1 behavior, remain safe under concurrent writers, and retain provenance to all source workflow IDs.
6. Existing workflow persistence and consumer contracts remain compatible and the applicable runtime tests pass.

## Non-Goals

- Desktop rendering or selection state.
- Mutating or deleting workflow history.
- Changing workflow retry/resume behavior.

## Dependency Notes

This is the foundation subtask and has no feature dependency.

## Validation Strategy

Use repository/application contract tests with multi-issue, blank-key, contradictory-state, malformed-artifact, retry-heavy, and performance/query-count fixtures. Run focused Gradle tests plus the full runtime check.

## Next Path

Proceed to desktop issue navigation and selection after the summary/detail port contracts are stable.

