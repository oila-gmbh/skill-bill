# SKILL-96 · Subtask 1 — Shapes tokenization

Parent overview: [spec.md](./spec.md)

This is the **first** subtask and the foundation. It makes every rounded surface
read from tokens so the systemic `MaterialTheme.shapes` and a small named
`SkillBillComponentShapes` replace ~75 inline `RoundedCornerShape(N.dp)` sites
and 3 `CircleShape` sites.

Branch: `feat/SKILL-96-m3-tokenization` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: `[]` (runs first)
- dependency_reason: Shapes are the most isolated dimension of tokenization
  (no type/dimension semantics to coordinate). Landing it first establishes the
  `SkillBillComponentShapes` direct-access convention that P2/P3 reuse for their
  own token objects, and removes shape literals from the same files P2/P3 will
  edit (reducing later merge noise).

## Scope (owns)

- **Rewrite `SkillBillShape.kt`** to add the M3 five-tier `ShapeScheme` as the
  systemic backbone and a named `SkillBillComponentShapes` object for the
  sub-tier radii. Target shape (values chosen so every literal in use maps
  losslessly — pixel layout preserved):

  ```kotlin
  val SkillBillShapeScheme = ShapeScheme(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
  )

  val SkillBillShapes = Shapes(
    extraSmall = SkillBillShapeScheme.extraSmall,
    small = SkillBillShapeScheme.small,
    medium = SkillBillShapeScheme.medium,
    large = SkillBillShapeScheme.large,
    extraLarge = SkillBillShapeScheme.extraLarge,
  )

  object SkillBillComponentShapes {
    val checkbox = RoundedCornerShape(2.dp)
    val chip = RoundedCornerShape(3.dp)
    val previewConsole = RoundedCornerShape(4.dp)
    val badge = RoundedCornerShape(5.dp)
    val control = RoundedCornerShape(6.dp)
    val panel = RoundedCornerShape(8.dp)
    val pill = CircleShape
  }
  ```

  Verify `androidx.compose.material3.ShapeScheme` is available at
  `composeMaterial3 = 1.10.0-alpha05`; if not, fall back to extending `Shapes`
  with extraSmall/extraLarge and keep `SkillBillComponentShapes` as-is.

- **Migration map (deterministic):**
  - 8dp → `MaterialTheme.shapes.medium` (dialogs, cards, sheets) or
    `SkillBillComponentShapes.panel`.
  - 6dp → `SkillBillComponentShapes.control` (buttons, text fields, chips,
    banners) or `MaterialTheme.shapes.small`.
  - 4dp → `SkillBillComponentShapes.previewConsole` (small console/preview boxes,
    small borders).
  - 5dp → `SkillBillComponentShapes.badge` (command-palette accent).
  - 3dp → `SkillBillComponentShapes.chip` (small chips, tree-row accents).
  - 2dp → `SkillBillComponentShapes.checkbox` (checkbox square, fine accents).
  - `CircleShape` → `SkillBillComponentShapes.pill` (status dots, close buttons).

- **Migrate every inline shape site** in the UI consumers. Representative sites
  (verify line numbers in planning — they drift): `ScaffoldWizardDialog.kt`
  (~28: 8dp/6dp/4dp), `ConfirmDeletionDialog.kt` (8dp/4dp/2dp), `SkillBillToolbar.kt`
  (6dp ×6, incl. 154/155/198/199/260/261), `SkillBillFrameShared.kt` (4dp/3dp/CircleShape),
  `SkillBillNavTree.kt` (3dp/CircleShape), `SkillBillEditorTabs.kt` (CircleShape/4dp),
  `SkillBillCodeEditor.kt` (6dp), `SkillBillNavigationPane.kt` (6dp),
  `SkillBillCommandPaletteOverlay.kt` (8dp/5dp), `SkillBillToolbarMenus.kt` (6dp),
  `SkillBillInspector.kt` (3dp/4dp), `FirstRunSetupDialog.kt` (8dp/6dp).

- **Extend `SkillBillThemeTokensTest.kt`** with assertions pinning the shape tiers
  (`SkillBillShapeScheme.small == RoundedCornerShape(6.dp)`, etc.) so the values
  cannot drift.

## Reusable patterns / pitfalls

- Use the **systemic tier** (`MaterialTheme.shapes.*`) for general M3 surfaces
  (dialogs/cards/sheets) and the **named component shape** for deliberately
  small/control surfaces — read intent at the call site.
- `CircleShape` is correct where the surface is genuinely circular; route it
  through `SkillBillComponentShapes.pill` for a single source of truth.
- No explanatory comments in code (project convention: comments are a code smell).
- Compile after each file; an unmatched import or leftover `RoundedCornerShape(`
  fails the `allWarningsAsErrors` build or the trailing grep gate.

## Acceptance Criteria

1. AC1: `SkillBillShape.kt` defines `SkillBillShapeScheme` (5-tier) and
   `SkillBillComponentShapes` (checkbox/chip/previewConsole/badge/control/panel/pill);
   `SkillBillShapes` is rebuilt from `SkillBillShapeScheme`.
2. AC2: No `RoundedCornerShape(` or `CircleShape` literal remains in
   `runtime-desktop/feature/skillbill/src/commonMain/.../ui/` or
   `runtime-desktop/core/ui/src/commonMain/.../ui/` (grep gate returns nothing).
3. AC3: Every rounded surface reads `MaterialTheme.shapes.*` or
   `SkillBillComponentShapes.*`; token values equal the literals they replace.
4. AC4: `SkillBillThemeTokensTest.kt` pins the shape-tier values.
5. AC5 (this subtask's gate): `(cd runtime-kotlin && ./gradlew check)` is green.

## Non-goals

- No typography, dimension, or string changes (P2/P3/P4).
- No change to which surfaces are rounded vs. square — only the source of the
  radius value.
- No new shapes/radii invented beyond the values already in use.

## Validation strategy

`bill-code-check` (routes Kotlin/Gradle → `(cd runtime-kotlin && ./gradlew check)`).
Then the shape grep gate:
`grep -rn "RoundedCornerShape(\|CircleShape" runtime-desktop/feature runtime-desktop/core/ui`
returns nothing (all shape construction now lives in the designsystem module).
Smoke the app (`:runtime-desktop:run`) over dialog/toolbar/tree/editor/palette to
confirm rounded surfaces render identically.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-96-m3-tokenization/spec_subtask_1_shapes.md`.
