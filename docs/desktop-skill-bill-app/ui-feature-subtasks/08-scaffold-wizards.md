# Subtask 08: Scaffold Entry Points and Wizards

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It introduces
the broadest write path and should run after validation, editor dirty-state, and
Git review are reliable.

## UI Entry Points

- New `New...` toolbar action.
- Context actions from relevant tree groups.
- Wizard dialogs or pages.
- Dry-run operations preview.
- Post-scaffold tree and Git status refresh.

## Goal

Add creation entry points that fit the existing UI without bypassing the
governed scaffolder.

## Scope

- Add a `New...` toolbar action and context actions from relevant tree groups.
- Implement wizard shells for:
  - horizontal skill
  - platform pack
  - platform override for piloted families
  - code-review area
  - add-on
- Build payloads matching
  `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- Run dry-run before apply when available.
- Show planned file and manifest operations.
- Execute scaffold through existing runtime behavior.
- Refresh tree, validation state, and Git status after successful scaffold.
- Surface scaffold runtime exception names/messages.
- Warn when the repo is dirty before scaffold unless runtime guarantees safe
  rollback with dirty state.

## Runtime and Service Requirements

- Use existing scaffold runtime behavior equivalent to `skill-bill new`.
- Scaffolder atomicity and loud-fail exceptions remain owned by runtime.
- The UI must not hand-write governed source outside the scaffolder.
- Wizard option lists should come from shared constants or runtime metadata
  where possible.

## Acceptance Criteria

- Every wizard produces contract-valid payload JSON.
- Dry-run and execute payloads differ only by execution mode.
- Dry-run displays planned file and manifest operations.
- Successful scaffold changes only expected authored source and manifest files.
- Failed scaffold shows runtime exception names/messages and leaves the repo in
  the runtime-guaranteed rollback state.
- Tree refresh shows the new artifact after successful scaffold.
- Generated wrappers are not offered for editing after scaffold.
- Dirty repo warnings are shown before scaffold execution.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a temporary repo copy.
2. Create a full platform pack.
3. Validate the repo.
4. Confirm generated wrappers are not checked in or editable.
5. Revert the temporary copy outside the app.

## Non-Goals

- Freeform manifest editing.
- Editing scaffolded generated output.
- Unsupported scaffold kinds.
- Publishing scaffold changes to Git.
