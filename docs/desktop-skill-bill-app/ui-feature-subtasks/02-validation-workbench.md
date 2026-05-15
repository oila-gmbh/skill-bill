# Subtask 02: Repository Validation Workbench

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It should be
implemented after Subtask 01 so validation can write into the shared app status
and dock badge model.

## UI Entry Points

- Top-toolbar `Validate` button.
- Left-panel `Validation` action.
- Inspector `Repository validation` section.
- Inspector `Validation issues` section.
- Bottom dock `Validation` tab.
- Validation status item in the bottom status bar.

## Goal

Run Skill Bill validation on demand and present the exact runtime validation
result coherently across toolbar, inspector, dock, and status bar.

## Scope

- Add a `ValidationGateway` or equivalent domain service.
- Run repo validation for the selected repo.
- Show running, passed, failed, and unavailable states.
- Display issue severity, code/name when available, message, source path, and
  runtime exception name.
- Allow selecting an issue to select or reveal the related tree item when the
  source maps to a known authored target.
- Preserve current editor draft during validation when Subtask 03 exists.
- Clear stale issue rows after a passing validation run.
- Keep validation results in app state so the inspector, dock, and status bar
  share the same counts.

## Runtime and Service Requirements

- Validation must call shared runtime behavior equivalent to
  `skill-bill validate --format json`.
- Desktop must not re-implement validation rules or infer pass/fail from file
  names.
- Runtime exception names and messages must remain visible.
- Validation should run through a service boundary, not directly from Compose.

## Acceptance Criteria

- Pressing `Validate` runs validation for the selected repo.
- The toolbar/action is disabled when no valid repo is open.
- Validation issues appear in the inspector and bottom dock with consistent
  counts.
- Validation pass clears stale issue rows.
- Validation failure does not crash and does not discard editor drafts.
- Issue source paths can be copied from the UI.
- Selecting a validation issue reveals the related target when the source maps
  to a visible tree item.
- The bottom status bar reflects the latest validation result.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a valid repo.
2. Run validation.
3. Confirm issue counts match in the inspector, dock tab, and status bar.
4. Introduce a temporary validation failure in a throwaway copy.
5. Run validation again and confirm the exact runtime message is visible.

## Non-Goals

- Saving editor drafts.
- Auto-validation after every keystroke.
- Git commit blocking.
- Runtime validation rule changes.
