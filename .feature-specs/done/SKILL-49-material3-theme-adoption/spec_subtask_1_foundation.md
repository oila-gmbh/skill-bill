# SKILL-49 Material 3 Theme Adoption - Subtask 1: Design-System Foundation

Status: Complete

Parent spec: [spec.md](spec.md)

## Scope

Establish the design-system theme surface that later subtasks will consume. This subtask owns token definitions and root theme behavior only; it should not migrate feature UI call sites except where a design-system API needs local examples or tests.

Implement or refine tokens in `runtime-kotlin/runtime-desktop/core/designsystem` so desktop UI can consume:

- Distinct light and dark Material 3 color schemes behind `SkillBillAppTheme` / `SkillBillTheme`.
- Semantic tone tokens for dialogs, scrims, warning banners, success banners, and error banners.
- Text-field tokens for text, placeholder, border, focus, disabled, container, and cursor colors.
- Named YAML syntax tokens and diff rendering tokens that can be used without importing `androidx.compose.ui.graphics.Color` from feature code.
- Branded component defaults that expose the tokens through existing `SkillBillTheme`, `MaterialTheme`, or core/designsystem components.

Preserve the existing root theme boundary shape: `SkillBillDesktopApp -> SkillBillAppTheme -> SkillBillWindow -> SkillBillRoute`.

## Acceptance Criteria

- AC1: `SkillBillAppTheme` remains the single root app theme boundary wrapping `SkillBillWindow` and feature routes.
- AC2: Design-system APIs expose the theme tokens needed for visible desktop surfaces.
- AC6: Text-field color sources exist for text, placeholder, border, focus, disabled, and cursor states.
- AC7: Semantic tone tokens exist for dialogs, scrims, warning banners, success banners, and error banners.
- AC8: Named syntax and diff tokens exist for YAML highlighting and diff rendering.
- AC9: Light and dark behavior supports distinct Material 3 schemes with readable contrast and tests.

## Non-Goals

- Do not redesign the desktop layout.
- Do not migrate `SkillBillFrame`, dialogs, or feature screens in this subtask.
- Do not add user-editable theme settings.
- Do not change runtime, scaffold, validation, render, install, or Git behavior.
- Do not introduce a feature flag; rollout is unflagged.

## Dependencies

None. This is the first subtask and provides the token surface required by all later subtasks.

## Validation Strategy

Run `bill-quality-check` for the repo-native validation path. At minimum, add or update focused tests under the desktop design-system/runtime test area to prove:

- Light and dark schemes are distinct.
- Required foreground/background token pairs meet readable contrast expectations.
- `SkillBillAppTheme` remains the only root app theme boundary outside core/designsystem.
- Text-field, semantic tone, syntax, and diff token groups are present and resolve through design-system APIs.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-49-material3-theme-adoption/spec_subtask_1_foundation.md`.
