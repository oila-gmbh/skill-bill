# SKILL-49 Material 3 Theme Adoption - Subtask 3: Dialogs and Smaller Surfaces

Parent spec: [spec.md](spec.md)

Status: Complete

## Scope

Migrate smaller visible desktop surfaces onto `SkillBillTheme`, `MaterialTheme`, or branded components from `core/designsystem`, using the token APIs and guardrails from subtasks 1 and 2.

This subtask should focus on files such as:

- `ScaffoldWizardDialog.kt`
- `FirstRunSetupDialog.kt`
- `ConfirmDeletionDialog.kt`
- Other dialog, setup, banner, and compact feature UI files in `runtime-kotlin/runtime-desktop/feature/skillbill/ui` that currently define local palettes or raw colors.

Replace local colors for dialogs, scrims, warning/success/error states, backdrop treatment, text fields, focus indicators, disabled states, and cursor colors with design-system tokens. Preserve existing state ownership, callbacks, snapshots, workflow copy, keyboard behavior, and destructive-action behavior.

## Acceptance Criteria

- AC2: Visible desktop dialog and setup surfaces consume `SkillBillTheme`, `MaterialTheme`, or branded design-system components.
- AC3: No migrated feature UI file defines a local color palette.
- AC4: No migrated desktop source file outside core/designsystem contains raw `Color(0x...)`, `Color.Black`, `Color.White`, or `Color.Transparent`.
- AC5: No migrated desktop source file outside core/designsystem imports `androidx.compose.ui.graphics.Color`.
- AC6: Text fields and `BasicTextField` wrappers source text, placeholder, border, focus, disabled, and cursor colors from theme tokens.
- AC7: Dialogs, scrims, warning banners, success banners, and error banners use semantic tone tokens.
- AC10: Existing UI behavior remains unchanged for editing, scaffold wizard, first-run setup, deletion preview, and related keyboard interactions.

## Non-Goals

- Do not redesign dialog layouts or dense workbench controls.
- Do not replace custom desktop components with generic Material components unless an existing branded component already fits.
- Do not migrate the large `SkillBillFrame.kt` workbench surface in this subtask.
- Do not localize strings or change workflow copy.
- Do not change runtime, scaffold, validation, render, install, or Git behavior.
- Do not introduce a feature flag; rollout is unflagged.

## Dependencies

Depends on subtask 1 for semantic and text-field tokens.
Depends on subtask 2 for guardrail tests and helper seams that prevent reintroducing raw feature colors.

## Validation Strategy

Run `bill-quality-check`. Add or update focused state-snapshot or Compose-level tests where existing infrastructure supports them, emphasizing behavior preservation rather than visual redesign:

- Scaffold wizard behavior and existing state transitions remain unchanged.
- First-run setup behavior remains unchanged.
- Deletion preview confirmation behavior remains unchanged.
- Dialogs and compact surfaces no longer contain raw color definitions or `Color` imports outside design-system.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-49-material3-theme-adoption/spec_subtask_3_dialogs-and-small-surfaces.md`.
