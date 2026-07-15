# SKILL-123.2 - Discovery, Product Classification, And Inventory

## Scope

Promote shared machine-skill discovery and inventory services. This subtask discovers all supported agent skill targets through existing runtime detection surfaces, classifies product, managed, unmanaged, conflicting, divergent, broken, corrupt, missing, hash-mismatched, and orphaned entries, and returns one immutable inventory snapshot used by desktop and future CLI consumers.

## Acceptance Criteria

1. Agent choices come from the shared runtime detector and preserve multiple target paths for the same provider, including multiple profile directories.
2. Target identity is provider plus normalized absolute skill-directory path, with enough display metadata to show detected, undetected, selected, missing, and conflicting target states.
3. Product Skill Bill skills are identified from active installation or protected product metadata, including `.bill-shared`, and are never classified by `bill-*` prefix alone.
4. The inventory groups discovered non-product skills into one logical row per normalized skill name across all detected agent targets.
5. Inventory rows report ownership, provenance, validation issues, content identity, per-target presence, link health, name collisions, and divergent same-name copies.
6. Managed rows prove ownership from managed records and expected snapshot links; unmanaged regular files, directories, and external symlinks remain read-only inventory facts.
7. Corrupt records, missing managed source, hash mismatches, broken owned links, and orphan snapshots are reported without destructive repair.
8. Product skills are excluded from the normal management list, with optional read-only diagnostic data available to callers.
9. Inventory code reuses foundation contract, record-store, bundle-scanner, target-identity, and hash primitives from subtask 1.

## Non-Goals

- Mutating agent directories or managed records.
- Creating install, update, adoption, edit, target-management, repair, or delete previews.
- Desktop catalog, wizard, manager, or navigator UI.
- Headless CLI commands.

## Dependency Notes

Depends on subtask 1. Mutation planning in subtask 3 must consume this inventory snapshot and must not rescan or reclassify independently.

## Validation Strategy

Add pure domain tests and filesystem integration tests covering multi-provider and multi-profile targets, product exclusion, managed ownership proof, unmanaged collisions, divergent same-name copies, broken links, corrupt records, missing source, hash mismatch, and orphan snapshots.

Run focused runtime tests for managed-skill inventory and affected detector services.

## Next Path

Continue with `spec_subtask_3_preview_transactions.md`.
