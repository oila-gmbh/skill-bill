# SKILL-123.3 - Mutation Preview, Preconditions, And Transaction Executor

## Scope

Build the shared mutation safety layer for machine-managed skills. This subtask defines immutable preview plans, stale-plan preconditions, cross-resource transaction journaling, rollback, post-mortems, symlink ownership proof, Windows symlink preflight, snapshot publication, and safe cleanup. It provides the executor used by install, update, edit, adoption, target management, repair, and deletion services.

## Acceptance Criteria

1. Install, update, adoption, edit, target-change, repair, and deletion can all produce read-only preview plans listing exact managed-source, record, snapshot, and agent-link creates, replacements, retargets, removals, unchanged outcomes, conflicts, and warnings.
2. Each plan captures no-follow path type and identity, raw and normalized symlink target, record digest/version, active hash, candidate bundle identity, target discovery identity, ownership proof, snapshot references, conflicts, and symlink capability.
3. Apply revalidates every captured precondition immediately before mutation and rejects the whole plan before writes when any precondition is stale.
4. Symlink operations never unlink by name alone; they verify expected type, expected target, and Skill Bill ownership before replacement or removal.
5. Snapshot publication stages and validates candidate source and snapshot content before active links change, publishes immutable content-addressed snapshots, and reuses only fully verified identical snapshots.
6. Record, source, snapshot, and link mutations are journaled across resources; recoverable failures roll back in reverse order.
7. When rollback cannot be proven complete, the executor persists an actionable machine-skill post-mortem containing affected paths, attempted operations, recovery state, and acknowledgement status.
8. Snapshot cleanup removes only snapshots with no discovered or recorded managed references.
9. Windows symlink preflight runs before any write and stops with elevation or Developer Mode guidance when symlinks are unavailable; no copy fallback exists.
10. Existing unmanaged regular files, directories, and external symlinks remain preserved conflicts unless a later adoption plan explicitly selects a replacement.

## Non-Goals

- Desktop UI and command palette wiring.
- Business-level install, edit, adopt, manage-targets, repair, or delete services beyond executor-level plan/apply primitives.
- Inventory discovery beyond consuming the snapshot from subtask 2.

## Dependency Notes

Depends on subtasks 1 and 2. Service subtasks must use this preview/apply engine rather than implementing bespoke filesystem mutation paths.

## Validation Strategy

Add filesystem integration tests with temporary homes for stale-plan rejection, symlink ownership checks, atomic record publication, snapshot reuse, interrupted apply phases, rollback, persistent post-mortems, unreferenced snapshot cleanup, unmanaged collision preservation, and Windows preflight behavior where the existing test harness supports it.

## Next Path

Continue with `spec_subtask_4_services.md`.
