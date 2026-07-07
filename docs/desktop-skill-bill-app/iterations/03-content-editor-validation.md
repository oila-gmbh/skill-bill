# Iteration 03: Content Editor and Validation

Status: Draft

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

For this iteration, the right panel must edit governed `content.md` text only.
Generated wrapper content may be previewed later only as read-only generated
output. Save, fill, render, and validation behavior must preserve runtime
semantics and error payloads.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Allow editing governed skill `content.md` through existing Skill Bill authoring operations, with validation feedback and dirty-state protection.

## User Value

A user can safely update authored skill content without touching generated wrappers or memorizing CLI commands.

## Scope

- Load full authored content for a selected skill.
- Show an editable Markdown text area in the right panel.
- Track saved text, draft text, and dirty state.
- Save through the existing fill/edit authoring operation.
- Show runtime validation results after save.
- Provide explicit repo validation action.
- Provide a rendered-preview action only if clearly labeled read-only generated output.

## Required Runtime Behavior

Use existing runtime operations equivalent to:

```bash
skill-bill show <skill-name> --repo-root <repo> --content full --format json
skill-bill fill <skill-name> --repo-root <repo> --body-file <file> --format json
skill-bill validate --repo-root <repo> --format json
skill-bill render <skill-name> --repo-root <repo>
```

The implementation should call shared services directly where possible. CLI process execution is acceptable only as a temporary adapter if the shared service does not yet exist, and the adapter must preserve the same payloads and exit semantics.

## Editor Rules

- The editor shows only authored `content.md` text for governed skills.
- The editor must not show generated wrapper sections as editable content.
- The editor must warn before discarding unsaved changes.
- Save failures must preserve draft text.
- Runtime validation messages should be displayed without rewriting them.

## Out of Scope

- Rich Markdown preview.
- Multi-file editing.
- Native-agent editing.
- Section-specific editing UI, unless the underlying full-file save is already complete.

## Acceptance Criteria

- Selecting a governed skill loads the full `content.md` body.
- Editing marks the document dirty.
- Saving writes through the runtime authoring operation.
- Runtime validation failures are shown inline and do not lose the draft.
- Running repo validation displays pass/fail and issue list.
- Generated `SKILL.md` remains unavailable for editing.

## Validation

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:test
./gradlew check
```

Manual smoke:

1. Open a temporary copy of this repo.
2. Select `bill-code-check`.
3. Add a small authored line in `content.md`.
4. Save.
5. Confirm only the expected authored source file changed on disk.
6. Run validation from the UI.

## Risks

- File watcher or refresh logic may overwrite editor drafts. Keep refresh explicit while the editor is dirty.
- Section-specific editing can corrupt authored structure if implemented before the full-file path is solid.
