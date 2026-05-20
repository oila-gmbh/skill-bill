# SKILL-49 Material 3 Theme Adoption - Subtask 4: Workbench Frame and Final Validation

Status: Complete

Parent spec: [spec.md](spec.md)

## Scope

Complete the migration by moving the large workbench frame and remaining desktop surfaces onto the design-system theme surface, then run final end-to-end validation for the full feature.

This subtask owns:

- Migrating `SkillBillFrame.kt` away from local `Workspace*` palettes, local `Tone` colors, local `diffLineColor`, command-palette raw colors, tree-row raw colors, toolbar/status colors, dock colors, and YAML palette wiring.
- Ensuring command palette fields and any custom `BasicTextField` wrappers use design-system text-field tokens for text, placeholder, border, focus, disabled, and cursor colors.
- Ensuring repo-open, validation, render, editing, changes dock, command palette, and keyboard accelerator behavior remains unchanged.
- Removing any remaining raw color usage and `Color` imports outside `runtime-kotlin/runtime-desktop/core/designsystem`.
- Running and fixing the final feature-level tests and checks.

## Acceptance Criteria

- AC1: `SkillBillAppTheme` remains the single root app theme boundary wrapping `SkillBillWindow` and feature routes.
- AC2: All visible desktop surfaces consume `SkillBillTheme`, `MaterialTheme`, or branded components from core/designsystem.
- AC3: No feature UI file defines a local color palette.
- AC4: No desktop source file outside core/designsystem contains raw `Color(0x...)`, `Color.Black`, `Color.White`, or `Color.Transparent`.
- AC5: No desktop source file outside core/designsystem imports `androidx.compose.ui.graphics.Color`.
- AC6: Text fields, including custom `BasicTextField` wrappers, source text, placeholder, border, focus, disabled, and cursor colors from theme tokens.
- AC7: Dialogs, scrims, warning banners, success banners, and error banners use semantic tone tokens.
- AC8: YAML syntax highlighting and diff rendering use named syntax/diff tokens.
- AC9: Light/dark behavior supports both distinct Material 3 schemes with readable contrast and tests.
- AC10: Existing UI behavior remains unchanged for repo open, validation, render, editing, scaffold wizard, first-run setup, deletion preview, changes dock, command palette, and keyboard accelerators.
- AC11: Tests fail if raw color usage is reintroduced outside the design-system module.

## Non-Goals

- Do not redesign the desktop layout.
- Do not replace dense workbench controls with generic Material components where custom desktop components fit better.
- Do not add user-editable themes or theme settings.
- Do not localize strings or change workflow copy.
- Do not change runtime, scaffold, validation, render, install, or Git behavior.
- Do not introduce a feature flag; rollout is unflagged.

## Dependencies

Depends on subtask 1 for the complete design-system token surface.
Depends on subtask 2 for YAML/diff helper seams and raw-color guardrails.
Depends on subtask 3 so smaller surfaces are already migrated before the large frame cleanup.

## Validation Strategy

Run `bill-quality-check` as the primary validation path. For final feature confidence, run the repo validation commands relevant to this desktop/Kotlin scope where feasible:

- `skill-bill validate`
- `(cd runtime-kotlin && ./gradlew check)`
- `npx --yes agnix --strict .`
- `scripts/validate_agent_configs`

Keep or add focused tests so failures catch:

- Reintroduced raw color usage or `Color` imports outside `core/designsystem`.
- Loss of distinct light/dark schemes or contrast regressions.
- Regressions in repo open, validation, render, editing, scaffold wizard, first-run setup, deletion preview, changes dock, command palette, or keyboard accelerator behavior.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-49-material3-theme-adoption/spec_subtask_4_frame-and-validation.md`.
