# SKILL-123.4 - Machine Skill Application Services

## Scope

Implement shared application services for installing, updating, editing, adopting, target reconciliation, repairing, deleting, and refreshing machine-managed third-party skills. These services consume discovery/inventory snapshots and the shared preview/transaction engine, and return typed plans and outcomes for desktop and future CLI callers.

## Acceptance Criteria

1. Install validates an opaque bundle, writes managed source under `~/.skill-bill/managed-skills/<name>/source`, writes a managed record, stages one immutable `~/.skill-bill/installed-skills/<name>-<hash>/` snapshot, and creates or retargets selected agent symlinks only.
2. Installing identical content for an already managed skill is idempotent and can update the selected agent set without duplicating source or snapshots.
3. Importing changed content for an existing managed skill is presented as an update and keeps the previous source, snapshot, record, and links active until the replacement is fully validated and ready.
4. Editing opens and saves only the canonical managed root `SKILL.md`, preserves supporting files, validates the candidate, stages a new snapshot, retargets selected owned links, updates the record, and leaves the prior state active on failure.
5. Manage agents adds conflict-free selected targets and removes only symlinks proven to be owned by the managed skill and expected snapshot.
6. Adoption imports an explicitly selected discovered source copy, validates it, and converts only explicitly selected equivalent copies to Skill Bill-managed symlinks.
7. Divergent same-name unmanaged copies require choosing the authoritative source and replacement targets before adoption.
8. Repair only repairs broken owned links when the record, managed source, and snapshot are healthy; corrupt records, missing source, hash mismatches, and orphan snapshots remain reported without destructive repair.
9. Delete previews every owned link, managed source path, record, and unreferenced snapshot to remove, requires explicit confirmation from the caller, revalidates the preview, and refuses changed link type or target state.
10. All services return typed per-target created, unchanged, retargeted, removed, skipped, warning, failed, conflict, and blocked outcomes.
11. Services reuse shared runtime/application boundaries; Compose or desktop code must not contain agent-path tables, ownership rules, content hashing, record parsing, symlink mutation logic, or product-skill classification.

## Non-Goals

- Desktop UI screens, command palette integration, or navigator rendering.
- Public marketplace, Git URL install, remote update feeds, or native-agent/MCP server installation.
- Editing arbitrary supporting files.
- Headless CLI commands.

## Dependency Notes

Depends on subtasks 1, 2, and 3. Desktop work in subtask 5 and navigator/editor work in subtask 6 should call these services through presentation-safe gateways.

## Validation Strategy

Add domain and filesystem integration tests for install, identical reinstall, changed update, all-vs-subset target selection, edit restaging, manage-target reconciliation, unmanaged collision preservation, equivalent and divergent adoption, broken-link repair, delete preview/apply, rollback/post-mortem, and end-to-end symlinks to one managed snapshot.

## Next Path

Continue with `spec_subtask_5_desktop_tools.md`.
