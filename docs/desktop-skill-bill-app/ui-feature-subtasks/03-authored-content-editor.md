# Subtask 03: Authored Content Editor

Status: Yo you did not implement this yet

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It introduces
the first repo write path, so it should land after Subtask 01 and preferably
after Subtask 02.

## UI Entry Points

- Center editor tab strip.
- Center editor/source text area.
- Line-number gutter and simple syntax coloring.
- Dirty marker support in the editor tab.
- Read-only banner.
- Inspector metadata and editability rows.
- Bottom status bar dirty/read-only indicators.

## Goal

Upgrade the read-only source viewer into a safe authored-source editor for
governed `content.md` targets while preserving generated artifact protections.

## Scope

- Load full authored `content.md` for selected governed skills.
- Edit text in the center panel for editable authored targets only.
- Track saved text, draft text, dirty state, save state, and save errors.
- Add Save and Revert controls only when the selected target is editable.
- Prevent selection changes that would discard unsaved edits without user
  confirmation.
- Prevent refresh from overwriting a dirty draft without user confirmation.
- Preserve draft text after save or validation failure.
- Show runtime validation or authoring errors inline without rewriting them.
- Keep generated artifacts and unsupported targets read-only.

## Runtime and Service Requirements

- Load through `AuthoringGateway` backed by shared runtime authoring behavior.
- Save through runtime operations equivalent to `skill-bill fill`.
- The UI may display file text only after runtime identifies the file as an
  authored target.
- Runtime error payloads must pass through to the UI without changing meaning.
- Generated wrappers and pointer files must never enter editable mode.

## Acceptance Criteria

- Selecting a governed skill loads its full `content.md`.
- Editing marks the tab and status bar dirty.
- Save writes through the runtime authoring operation.
- Revert restores the latest saved text.
- Save failure leaves the draft intact and displays the runtime error.
- Selection changes and refresh while dirty require discard or cancel.
- Generated `SKILL.md`, generated support pointers, provider-specific native
  output, and install cache files cannot enter editable mode.
- Git status refreshes after successful save when Subtask 05 exists.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a temporary copy of this repo.
2. Select `bill-quality-check`.
3. Edit its authored `content.md`.
4. Save.
5. Confirm Git shows only the expected authored source file changed.
6. Attempt to select a generated artifact and confirm it is read-only.

## Non-Goals

- Rich Markdown preview.
- Multi-file editing.
- Native-agent editing.
- Section-specific editing UI.
- Git commit and push.
