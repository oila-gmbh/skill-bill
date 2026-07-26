# SKILL-96 · Subtask 2 — Typography tokenization

Parent overview: [spec.md](./spec.md)

Replaces ~151 inline `fontSize`/`fontWeight` literals and inline `TextStyle(...)`
constructors with `MaterialTheme.typography.*` slots and a small extended
`SkillBillTypeStyles` object.

Branch: `feat/SKILL-96-m3-tokenization` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: `[1]` (shapes)
- dependency_reason: P1 already touched most of these files and established the
  direct-access token-object convention. P2 layers typography tokens on top,
  avoiding merge conflicts from concurrent shape+type edits in the same files.

## Scope (owns)

- **New `SkillBillTypeStyles.kt`** — a direct-access `object` (same convention as
  `SkillBillMetrics`) holding only the genuinely non-standard styles. The common
  UI sizes (12/14/16 sp) already match M3 slots, so they are NOT duplicated here.
  Target:

  ```kotlin
  object SkillBillTypeStyles {
    val code        = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 20.sp)
    val codeCaption = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 16.sp)
    val lineNumber  = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val caption     = TextStyle(fontSize = 10.sp)
    val body13      = TextStyle(fontSize = 13.sp)
    val microLabel  = TextStyle(fontSize = 8.sp)
    val chipLabel   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
  }
  ```

  (`lineHeight` for `codeCaption` chosen to match the existing inline value;
   verify against `ScaffoldWizardDialog.kt`/`SkillBillCodeEditor.kt` in planning.)

- **Retune `titleSmall`** in `SkillBillTypography.kt` from Bold to Medium
  (`titleSmall = defaultTypography.titleSmall.copy(fontWeight = FontWeight.Medium,
  fontSize = 14.sp)`) so 14sp panel titles read straight off the slot. This retune
  lands in the same subtask as its migration so nothing reads a stale default.
  Verify in smoke that panel headers and any M3 component defaulting to
  `titleSmall` still render correctly.

- **Migration map (deterministic; prefer existing M3 slots to avoid systemic
  churn):**
  - 12sp body (×41) → `MaterialTheme.typography.bodySmall` (already 12sp Medium).
  - 14sp titles (×7) → `MaterialTheme.typography.titleSmall` (after retune).
  - 16sp (×1) → `MaterialTheme.typography.bodyLarge` (already 16sp Medium).
  - 11sp (×44) → `MaterialTheme.typography.labelSmall` (already 11sp) for general
    captions, or `SkillBillTypeStyles.chipLabel` where a chip/medium label is
    intended.
  - 12.5sp Monospace + 20sp line (×9) → `SkillBillTypeStyles.code`.
  - 10.5sp Monospace (×6) → `SkillBillTypeStyles.codeCaption`.
  - 10sp (×14) → `SkillBillTypeStyles.caption`.
  - 13sp (×2) → `SkillBillTypeStyles.body13`.
  - 8sp (×1) → `SkillBillTypeStyles.microLabel`.
  - Inline `TextStyle(...)` constructors (code editor YAML coloring, command
    palette, navigation pane) collapse to the appropriate `SkillBillTypeStyles.*`
    or M3 slot.

- **Migrate every inline type site** in the UI consumers. Worst offenders
  (verify line numbers in planning): `ScaffoldWizardDialog.kt` (~40),
  `FirstRunSetupDialog.kt` (~24), `ConfirmDeletionDialog.kt` (~20),
  `SkillBillCodeEditor.kt` (~14, incl. monospace YAML coloring at ~99-103/362-385),
  `SkillBillInspector.kt`, `SkillBillNavigationPane.kt`,
  `SkillBillCommandPaletteOverlay.kt`, `SkillBillNavTree.kt`, `SkillBillEditorTabs.kt`,
  `SkillBillFrameShared.kt`, `SkillBillStatusBar.kt`, `SkillBillToolbar.kt`,
  `SkillBillToolbarMenus.kt`.

- **Extend `SkillBillThemeTokensTest.kt`** with assertions pinning the retuned
  `titleSmall` and the extended `SkillBillTypeStyles` values.

## Reusable patterns / pitfalls

- Prefer the M3 slot when the size already matches (12/14/16/11 sp) — do not
  duplicate systemic sizes in the extended object.
- Monospace code styles are the one place a custom style is genuinely needed;
  collapse the duplicated `FontFamily.Monospace` + `12.5.sp` blocks into one
  `SkillBillTypeStyles.code`.
- `letterSpacing = 0.sp` literals that accompany some inline styles: explicit
  `0.sp` is the default — drop it unless the slot/style needs a non-zero value.
- The `titleSmall` retune changes the default for any M3 component reading it;
  smoke all panels after migration.
- No explanatory comments in code.

## Acceptance Criteria

1. AC1: `SkillBillTypeStyles.kt` defines the extended non-standard styles; it is a
   direct-access `object` (matching the `SkillBillMetrics` convention), not routed
   through the `SkillBillTheme` CompositionLocal facade.
2. AC2: `titleSmall` is retuned to Medium/14sp in `SkillBillTypography.kt`.
3. AC3: No `fontSize =`, `fontWeight =` literal, or inline `TextStyle(...)`
   constructor remains in `runtime-desktop/feature/skillbill/src/commonMain/.../ui/`
   or `runtime-desktop/core/ui/src/commonMain/.../ui/` (grep gate returns nothing).
4. AC4: Every `Text` reads `MaterialTheme.typography.*` or a `SkillBillTypeStyles.*`
   style; rendered sizes/weights equal the literals they replace.
5. AC5: `SkillBillThemeTokensTest.kt` pins `titleSmall` and the extended styles.
6. AC6 (this subtask's gate): `(cd runtime-kotlin && ./gradlew check)` is green.

## Non-goals

- No shape, dimension, or string changes (P1 done; P3/P4 later).
- No new type scales invented beyond the sizes already in use.
- No font/FontFamily change for the systemic slots (system default stays).

## Validation strategy

`bill-code-check` (routes Kotlin/Gradle → `(cd runtime-kotlin && ./gradlew check)`).
Then the typography grep gate:
`grep -rn "fontSize =\|fontWeight =\|TextStyle(" runtime-desktop/feature runtime-desktop/core/ui`
returns nothing except inside the designsystem module. Smoke the app over every
dialog/editor/tree/palette to confirm text sizes/weights are unchanged and the
`titleSmall` retune did not regress any panel header.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-96-m3-tokenization/spec_subtask_2_typography.md`.
