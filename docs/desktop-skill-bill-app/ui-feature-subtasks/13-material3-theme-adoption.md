# Subtask 13: Material 3 Theme Adoption

Status: Draft

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-task-specs.md`. The desktop
app already installs a Material 3 theme at the root, but the feature UI still
uses parallel local palettes such as `Workspace*`, `ScaffoldDialog*`, and
`Setup*`. This subtask turns the existing theme wrapper into the single design
system used by the whole desktop app.

## Current Problem

`SkillBillAppTheme` wraps the desktop app, and `SkillBillMaterialTheme`
provides a Material 3 `ColorScheme`, typography, and shapes. Most visible UI
does not consume those tokens. Large surfaces define their own colors, font
sizes, shapes, and component styling inline, which creates duplicate theme
layers and makes small issues, such as invisible input carets, recur in every
custom field.

The goal is not to make the UI look generically Material. The app can keep its
dense workbench layout and branded visual identity. The requirement is that
every color and reusable style decision flows through a single Material 3 based
design system.

## Goal

Implement a state-of-the-art Material 3 theme for the desktop app and migrate
the whole app to consume it, with no hardcoded color values outside the
design-system source of truth.

## Scope

- Keep `SkillBillAppTheme` as the root theme entry point and make it the only
  app-wide theme boundary.
- Replace the current partial theme with a complete desktop Material 3 theme:
  - real dark and light `ColorScheme` values, or an explicit product decision
    that the app is dark-only and always uses the dark scheme
  - typography roles for dense desktop surfaces, code/editor text, labels,
    section headings, status text, and dialog titles
  - shapes for panels, dialogs, buttons, input fields, chips, badges, and small
    tree markers
  - spacing and sizing tokens for app shell metrics, toolbar controls,
    inspector rows, dock tabs, dialogs, banners, and form rows
  - semantic tone tokens for success, warning, error, info, generated
    artifacts, read-only state, selected rows, hover, focus, disabled, and
    active accents
  - syntax/diff tokens for YAML, generated artifact markers, additions,
    deletions, hunks, comments, strings, keys, and scalars
- Provide reusable component defaults or small branded components for:
  - toolbar buttons and icon buttons
  - segmented choices / preset pickers
  - text fields, including `BasicTextField` wrappers that need custom dense
    layout
  - dialog panels, dialog actions, and scrims
  - status badges, validation banners, and tone pills
  - tree rows, selected rows, hover rows, and resize handles
  - dock tabs and tab badges
- Migrate all feature UI surfaces to consume `SkillBillTheme`,
  `MaterialTheme`, and design-system components instead of local palettes:
  - `SkillBillFrame.kt`
  - `ScaffoldWizardDialog.kt`
  - `FirstRunSetupDialog.kt`
  - `ConfirmDeletionDialog.kt`
  - syntax highlighter and diff rendering helpers
  - shared core UI chrome
- Remove local color aliases such as `Workspace*`, `ScaffoldDialog*`,
  `Setup*`, and `Dialog*` once the equivalent semantic tokens exist in the
  design system.
- Add guardrails that fail the build when desktop UI code reintroduces raw
  color literals or direct `Color` usage outside the design-system module.

## Color Ownership Rule

No desktop app feature or core UI source file may contain raw color literals.
The only allowed source of hardcoded color values is the design-system module
where the Material 3 schemes and semantic tokens are defined.

Concretely:

- `Color(0x...)`, `Color.Black`, `Color.White`, `Color.Transparent`, and other
  direct `Color` constants are forbidden outside
  `runtime-desktop/core/designsystem`.
- Feature UI should not import `androidx.compose.ui.graphics.Color` unless the
  file is part of the design-system implementation.
- Alpha variants must be exposed as named theme tokens when reused. One-off
  alpha calls are allowed only inside the design-system module.
- Custom `BasicTextField`, `Canvas`, syntax, and diff rendering code must still
  use theme tokens; custom drawing is not an exemption.

## Implementation Plan

1. Add a failing architecture test that scans `runtime-desktop` source for raw
   color usage outside `core/designsystem`.
2. Expand the design-system module with complete Material 3 tokens:
   color schemes, extended semantic colors, typography, shapes, spacing,
   component defaults, and syntax/diff tones.
3. Replace feature-local palettes with theme reads and reusable component
   wrappers. Start with the smallest isolated dialogs, then migrate
   `SkillBillFrame.kt`.
4. Remove obsolete local palette declarations and any comments that describe
   them as temporary follow-ups.
5. Add focused UI tests for the previously fragile cases:
   visible text-field caret, selected/disabled button contrast, dialog scrim
   and panel colors, syntax/diff tone mapping, and light/dark theme behavior
   if both modes are supported.
6. Run visual smoke checks for the main workbench, scaffold wizard,
   first-run setup, deletion dialog, editor, changes dock, and validation dock.

## Acceptance Criteria

- The app still has exactly one root app theme boundary:
  `SkillBillAppTheme` wrapping `SkillBillWindow` and feature routes.
- All visible desktop surfaces consume `SkillBillTheme`, `MaterialTheme`, or
  branded components from `core/designsystem`.
- No feature UI file defines a local color palette.
- No desktop source file outside `core/designsystem` contains `Color(0x...)`,
  `Color.Black`, `Color.White`, or `Color.Transparent`.
- No desktop source file outside `core/designsystem` imports
  `androidx.compose.ui.graphics.Color`.
- Text fields, including custom `BasicTextField` wrappers, get text,
  placeholder, border, focus, disabled, and cursor colors from theme tokens.
- Dialogs, scrims, warning banners, success banners, and error banners get all
  colors from semantic tone tokens.
- YAML syntax highlighting and diff rendering get all colors from named
  syntax/diff tokens.
- The light/dark behavior is explicit and tested:
  - if both modes are supported, they use distinct Material 3 schemes with
    readable contrast
  - if the product stays dark-only, the theme intentionally ignores system
    light mode and tests pin that decision
- Existing UI behavior remains unchanged: repo open, validation, render,
  editing, scaffold wizard, first-run setup, deletion preview, changes dock,
  command palette, and keyboard accelerators still work.
- Tests fail if raw color usage is reintroduced outside the design-system
  module.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:designsystem:jvmTest
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest
./gradlew --no-configuration-cache :runtime-desktop:jvmTest
./gradlew check
```

Manual smoke:

1. Launch the app and open a Skill Bill checkout.
2. Verify the main workbench, tree, toolbar, inspector, editor, docks, and
   status bar render with the same visual identity as before.
3. Open the scaffold wizard, first-run setup, and deletion dialog; verify
   text, borders, buttons, selected states, disabled states, and carets are
   readable.
4. Inspect YAML and diff views; verify additions, deletions, hunks, comments,
   keys, strings, and generated-artifact warnings remain distinguishable.
5. Switch OS light/dark mode if the app supports both schemes, or confirm the
   pinned dark-only decision if it does not.

## Non-Goals

- Redesigning the desktop app layout.
- Replacing dense workbench controls with generic Material components when a
  custom desktop component is more ergonomic.
- Introducing user-editable themes or a theme settings screen.
- Localizing strings or changing workflow copy.
- Changing runtime, scaffold, validation, render, install, or Git behavior.

## Risks

- A strict no-raw-color guard can expose many small custom drawing sites.
  Treat those as token gaps, not as reasons to weaken the rule.
- A full `SkillBillFrame.kt` migration is broad. Keep changes mechanical and
  token-for-token where possible before making visual refinements.
- Real light mode may require more contrast work than simply mirroring the
  dark palette. If light mode is not ready, make dark-only an explicit product
  decision and pin it in tests.
