# Subtask 07: Command Search and Quick Open

Status: Draft

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It should run
after the major commands it exposes have real ViewModel handlers.

## UI Entry Points

- Top-right search box labeled `Find skill, intent, contract id...`.
- Future keyboard shortcut shown as the command palette hint.

## Goal

Provide fast keyboard-first navigation across loaded repo targets and visible
commands without inventing a second discovery model.

## Scope

- Turn the static search box into a command palette.
- Search loaded tree items by label, skill name, authored path, kind, platform,
  family, area, and generated-artifact path.
- Support commands for visible toolbar/dock actions:
  - Open repository
  - Refresh tree
  - Validate repository
  - Render check selected target
  - Show changes
  - Show history
  - Save current editor draft when Subtask 03 exists
- Selecting a result must route through the same tree-selection handler used by
  mouse selection.
- Empty repo state should show only repo/session commands.
- Commands must be hidden or disabled when prerequisites are missing.

## Runtime and Service Requirements

- Search index is built from current `SkillBillState`.
- Runtime discovery remains owned by repo open/refresh.
- Commands call the same ViewModel command functions as visible UI actions.
- No command mutates repo content without an explicit confirmation or existing
  save/scaffold/publish flow.

## Acceptance Criteria

- Opening the palette focuses a text input and shows ranked results.
- Search results select the same item ids used by the left tree.
- Running `Refresh` or `Validate` from the palette produces the same state as
  pressing the visible button.
- Palette results update after repo open, refresh, validation, save, scaffold,
  and Git status refresh.
- Disabled commands explain the missing prerequisite.
- Keyboard navigation can choose and execute a result.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open this repository.
2. Search for a known skill.
3. Select it from the palette.
4. Run Validate from the palette.
5. Confirm results match visible-button behavior.

## Non-Goals

- Fuzzy search service outside app state.
- Runtime discovery changes.
- Background indexing.
- Arbitrary shell command execution.
