# Subtask 12: Keyboard Accelerators

Status: Draft

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. The desktop
shell currently wires exactly one accelerator (`Ctrl/Cmd+K` and
`Ctrl/Cmd+P` for the command palette) and otherwise forces pointer use for
every workspace action. This subtask adds the small set of accelerators that
mirror the existing toolbar / dock buttons.

## UI Entry Points

- The root frame `onPreviewKeyEvent` that already handles the command
  palette shortcut.
- The Repository path `BasicTextField` in the Repository selector.
- The commit message `BasicTextField` in the Changes dock tab.

## Goal

Cover the everyday workspace actions with platform-conventional accelerators
that route through the same callbacks the toolbar and dock buttons already
use, without adding new view-model surface area and without re-introducing
the "shortcut runs while busy" footgun.

## Scope

- Repository path field: pressing `Enter` (and `NumPadEnter`) runs the same
  flow as the adjacent open icon, equivalent to `onRepoSelected(repoPath)`.
- Commit message field: pressing `Cmd/Ctrl+Enter` runs `onCommit` only when
  `canCommit && !publishingBusy`. Plain `Enter` continues to insert a
  newline.
- Global accelerators (frame-level `onPreviewKeyEvent`), gated on
  `canStartRepoScopedAction()` and the same per-action `enabled` predicates
  the toolbar uses:
  - `Cmd/Ctrl+S` → `onEditorSave` when the editor is dirty and editable.
  - `Cmd/Ctrl+R` → `onRefresh` when no busy operation is in flight.
  - `Cmd/Ctrl+Shift+R` → `onRender` when render is enabled.
  - `Cmd/Ctrl+Shift+V` → `onValidate` when validate is enabled.
- Every accelerator must respect the existing `busy` / `publishingBusy` /
  `dirtyEditorPrompt` predicates the equivalent buttons already check.
- Existing palette shortcut behavior must remain unchanged.
- Discoverability: the toolbar buttons gain a tooltip (existing
  `TooltipArea` pattern already used by the read-only artifact tooltip) that
  shows the accelerator on hover. The command palette result rows for the
  same actions show the accelerator inline.

## Runtime and Service Requirements

- No new runtime services or view-model methods. All accelerators must
  route through callbacks already exposed by `SkillBillRoute`.
- Accelerator dispatch must remain on the UI dispatcher; the existing
  `begin/run/finish` pattern in the route handlers continues to gate the
  actual heavy work.

## Acceptance Criteria

- Pressing `Enter` in the repository path field opens the typed repo path
  exactly as clicking the open icon would, including the dirty-editor prompt
  guard.
- Pressing `Cmd/Ctrl+Enter` in the commit message field commits when
  `canCommit && !publishingBusy`; pressing it otherwise is a no-op.
- Plain `Enter` in the commit message field still inserts a newline.
- `Cmd/Ctrl+S` triggers save only when the editor is editable and dirty.
- `Cmd/Ctrl+R` and `Cmd/Ctrl+Shift+R` trigger refresh and render only when
  their existing enabled predicates allow it.
- `Cmd/Ctrl+Shift+V` triggers validate only when validate is enabled.
- All accelerators are no-ops while `busyOperation != null` or
  `publishingBusy` is true, matching the toolbar.
- The command palette shortcut still works exactly as before.
- Toolbar buttons that have an accelerator show that accelerator in their
  tooltip; command palette result rows for the same actions also show it.
- Tests cover at least one positive and one disabled path per accelerator,
  using the existing fakes.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Type a repo path and press `Enter` — repo opens.
2. Edit an authored source and press `Cmd+S` — file saves and the
   Changes tab badge updates.
3. Press `Cmd+R` — repo refreshes.
4. Press `Cmd+Shift+R` — render runs when enabled.
5. Press `Cmd+Shift+V` — validation runs when enabled.
6. In the commit message field, press `Enter` (newline) then
   `Cmd+Enter` (commit).

## Non-Goals

- Rebindable shortcuts or a settings UI.
- Vim/Emacs-style multi-key shortcuts inside the editor.
- IDE-style auto-save on focus loss.
- New view-model commands that don't already exist.
