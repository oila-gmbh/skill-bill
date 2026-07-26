# SKILL-96 — Material 3 tokenization: close every hardcoded-value gap

Issue key: SKILL-96
Lineage: continues SKILL-49-material3-theme-adoption (the foundational M3 color
scheme, typography object, shapes object, and `SkillBillTheme` facade). Colors
and elevation landed clean there; this spec closes the tokenization gaps that
remain in the UI consumers.

## Context

The SkillBill desktop UI has a mature, centralized Material 3 design system in
`runtime-kotlin/runtime-desktop/core/designsystem/`. The M3 `ColorScheme`
(light + dark, all roles), the `Typography` object (13 slots), the `Shapes`
object, the `SkillBillMetrics` object, and the `SkillBillTheme` facade are all
in place, and **colors are 100% tokenized** in UI composables (every surface
reads `SkillBillTheme.frameTokens.*` / `.colors.*` / `.semanticTones.*`; zero
inline `Color(...)` literals).

But three of the token objects are **defined and then bypassed**: nearly every
`@Composable` reaches for inline `RoundedCornerShape(N.dp)`, `fontSize = N.sp`,
and `N.dp` literals instead of the tokens. And there is **no string-resource
layer at all** — every user-facing string is an inline literal.

Intended outcome: the UI follows M3 standards and is **100% tokenized** — no
hardcoded shapes, typography, dimensions, or strings anywhere in feature/core-ui
composables.

## Problem

A full audit (2026-06-28) of all 30 UI `.kt` files under
`runtime-desktop/feature/skillbill/.../ui/` and `runtime-desktop/core/ui/.../ui/`
found four classes of tokenization debt:

1. **Shapes (~78 sites).** `SkillBillShapes` / `MaterialTheme.shapes` are
   referenced **0×** in feature or core-ui composables. Every rounded surface is
   an inline `RoundedCornerShape(N.dp)` (75 uses; radii 2/3/4/5/6/8 dp) plus 3
   `CircleShape`. The 3-tier token (`small=6`, `medium=8`, `large=0`) cannot even
   represent 2/3/4/5 dp.
2. **Typography (~151 sites).** `SkillBillTypography` is referenced **0×** in UI.
   Every `Text` builds its style inline (`fontSize = N.sp` ×125,
   `fontWeight = …` ×26, plus inline `TextStyle(...)` constructors). Sizes in
   use: 11sp ×44, 12sp ×41, 10sp ×14, 12.5sp ×9, 14sp ×7, 10.5sp ×6, 13sp ×2,
   8sp ×1, 16sp ×1.
3. **Dimensions (~306 `.dp` literals).** `SkillBillMetrics` is referenced only
   2× (`SkillBillNavigationPane.kt` tree pane width, `SkillBillInspector.kt`
   inspector pane width). There is no spacing/padding/border/size token system,
   so `padding/border/size/height` values are repeated inline hundreds of times —
   e.g. `SkillBillToolbar.kt` hardcodes `40.dp` that `SkillBillMetrics.toolbarHeight`
   already provides.
4. **Strings (no resource system).** No `strings.xml`, no `Res.string`, no
   `stringResource(...)` anywhere; the `compose.resources` feature is not
   enabled. ~130 `Text("…")` literals plus hundreds of `label=` / `title=` /
   `tooltip=` / `contentDescription=` literals are inline. Three ad-hoc Kotlin
   `object` holders (`ScaffoldWizardStrings`, `ConfirmDeletionStrings` in
   feature/skillbill; `SkillBillAcceleratorLabels` in core/domain) centralize a
   fraction of the copy. Code comments explicitly say "when a localization layer
   lands".

Colors and elevation were audited and are **clean** — they are non-goals here.

## Goals

1. Make every UI composable read shapes, typography, and dimensions from tokens.
2. Introduce a Compose Multiplatform resources layer and migrate every
   user-facing string to `stringResource(Res.string.*)`.
3. Leave colors and elevation untouched (already compliant).
4. Land the work as four independently-mergeable subtasks, each compiling and
   testing green on its own, so the blast radius of each phase is bounded.

## Non-goals

- No change to the brand palette. The hand-defined Yellow/Green/Red/Amber/Ink
  palette stays exposed via light/dark `ColorScheme` + extended tokens (the M3
  pattern for extra colors). Inline color usage is already 0%.
- No new product features, no behavioral change, no layout redesign. This is a
  tokenization/lift migration; pixel layout must be preserved (tokens map to the
  same numeric values the literals held).
- No screenshot/golden-image test infrastructure (none exists). Visual
  regression is caught by manual smoke.
- Not migrating `SkillBillStatusBar.READ_ONLY_MODE_LABEL` and other
  compile-time-constant string markers out of Kotlin `const val` (they are
  non-display contract values, not localized copy).

## Target architecture / approach

All new/edited token files live in
`runtime-kotlin/runtime-desktop/core/designsystem/src/commonMain/kotlin/skillbill/desktop/core/designsystem/`.
A convention correction: the existing **non-theme token objects** are accessed
**directly** at the call site (`SkillBillMetrics.treePaneWidth`), not through the
`SkillBillTheme` CompositionLocal facade. The new custom token objects
(`SkillBillShapeScheme`, `SkillBillComponentShapes`, `SkillBillTypeStyles`,
`SkillBillDimens`) follow that same direct-access convention. Only the systemic
M3 slots are read via `MaterialTheme.typography` / `MaterialTheme.shapes`.

### Phase 1 — Shapes
Rewrite `SkillBillShape.kt` to add the M3 five-tier `ShapeScheme`
(extraSmall=4, small=6, medium=8, large=12, extraLarge=16 dp) as the systemic
backbone, plus a small `SkillBillComponentShapes` object for the genuinely
sub-tier radii the three-tier `Shapes` cannot represent without erasing
hierarchy (`checkbox=2`, `chip=3`, `previewConsole=4`, `control=6`, `panel=8`,
`pill=CircleShape`). Migrate all ~75 `RoundedCornerShape(N.dp)` and 3
`CircleShape` sites to the systemic tier (`MaterialTheme.shapes.*`) or the named
component shape.

### Phase 2 — Typography
Map inline sizes to the **existing M3 slots wherever they already match**
(`bodySmall`=12sp Medium, `bodyMedium`=14sp Medium, `bodyLarge`=16sp Medium,
`labelSmall`=11sp, `titleSmall`=14sp) to avoid systemic churn, and add a small
extended `SkillBillTypeStyles` object only for the genuinely non-standard sizes
(`code` = 12.5sp Monospace / 20sp line, `codeCaption` = 10.5sp Monospace,
`lineNumber` = 12sp Monospace, `caption` = 10sp, `microLabel` = 8sp,
`body13` = 13sp). Retune `titleSmall` weight Bold→Medium so 14sp panel titles
read straight off the slot. The retune and its migration land in one subtask.

### Phase 3 — Dimensions
Add `SkillBillDimens` (4dp-grid spacing/padding/border/size primitives) and
extend `SkillBillMetrics` with the remaining structural sizes (reusing the
existing `statusPaneHeight = 28.dp`; adding `editorCommandBarHeight=38`,
`footerHeight=52`, `dialogHeaderHeight=44`). Migrate all ~306 `.dp` literals to
the nearest token. After P1–P3, drop the `@file:Suppress("MagicNumber")`
annotations that exist precisely to hide these literals.

### Phase 4 — Strings
Enable the Compose Multiplatform resources feature (`org.jetbrains.compose` is
already applied) with `publicResClass = true` so the generated `Res` is visible
to `feature:skillbill` and `core:ui`, not just `designsystem`. Add a single
source of truth at `designsystem/src/commonMain/composeResources/values/strings.xml`
and migrate every user-facing literal to `stringResource(Res.string.*)`
(templated strings use `%1$s` args). Retire the three ad-hoc string objects.

## Acceptance Criteria

1. No `RoundedCornerShape(` or `CircleShape` literal remains in
   `runtime-desktop/feature/.../ui/` or `runtime-desktop/core/ui/.../ui/`;
   every rounded surface reads `MaterialTheme.shapes.*` or
   `SkillBillComponentShapes.*`.
2. No `fontSize =` / `fontWeight =` literal or inline `TextStyle(...)` remains in
   UI composables; every `Text` reads `MaterialTheme.typography.*` or a
   `SkillBillTypeStyles.*` style.
3. No `N.dp` literal remains in UI composables for padding/border/size/height;
   every dimension reads `SkillBillDimens.*` or `SkillBillMetrics.*`.
4. No user-facing string literal remains in UI composables (`Text("…")`,
   `label=`, `title=`, `tooltip=`, `contentDescription=`); all read
   `stringResource(Res.string.*)` from a real `strings.xml`.
5. `@file:Suppress("MagicNumber")` is removed from `SkillBillToolbar.kt`,
   `ScaffoldWizardDialog.kt`, and any other UI file that carried it; detekt
   (via `skillbill.quality`) reports no MagicNumber findings in UI.
6. Pixel layout is preserved: token values equal the literals they replace;
   `(cd runtime-kotlin && ./gradlew check)` is green and the app smokes clean.
7. The `SkillBillTheme` facade remains the single entry for CompositionLocal-driven
   values; new custom token objects are direct top-level accessors (matching the
   `SkillBillMetrics` precedent).

## Files expected to change (non-exhaustive)

Token infra (designsystem module):
- `…/designsystem/SkillBillShape.kt` — rewrite (add `SkillBillShapeScheme` + `SkillBillComponentShapes`)
- `…/designsystem/SkillBillTypeStyles.kt` — new (P2)
- `…/designsystem/SkillBillDimens.kt` — new (P3)
- `…/designsystem/SkillBillMetrics.kt` — extend (P3; keep existing fields)
- `…/designsystem/SkillBillTypography.kt` — retune `titleSmall` (P2)
- `…/designsystem/composeResources/values/strings.xml` — new (P4)
- `runtime-desktop/core/designsystem/build.gradle.kts` (+ `build-logic/.../KmpComposeConventionPlugin.kt`
  only if needed) — enable `compose.resources` `publicResClass` (P4)

UI consumers (feature/skillbill/.../ui/ + core/ui/.../ui/):
`ScaffoldWizardDialog.kt`, `ConfirmDeletionDialog.kt`, `FirstRunSetupDialog.kt`,
`SkillBillToolbar.kt` (already modified in the working tree — reconcile), `SkillBillToolbarMenus.kt`,
`SkillBillCodeEditor.kt`, `SkillBillFrameShared.kt`, `SkillBillNavTree.kt`,
`SkillBillEditorTabs.kt`, `SkillBillNavigationPane.kt`, `SkillBillCommandPaletteOverlay.kt`,
`SkillBillInspector.kt`, `SkillBillStatusBar.kt`, `WindowChrome.kt` (verify only).

Tests to extend: `SkillBillThemeTokensTest.kt`, `SkillBillFrameTokenWiringTest.kt`,
`SkillBillDesktopAppThemeBoundaryTest.kt`.

## Risks

- **Systemic-slot churn (P2).** Retuning `titleSmall` changes the default for any
  M3 component that reads it; verify panel headers and any M3 component still
  render correctly in the manual smoke.
- **Migration volume.** ~600 change sites across 14 files. Mitigate by doing the
  work phase-by-phase, compiling each module after each file, and using grep
  gates to prove completeness before merging a phase.
- **`stringResource` is `@Composable` (P4).** Non-composable helpers that today
  return string literals (e.g. enum-label `when` functions) must become
  `@Composable` or receive already-resolved strings. Audit `jvmTest` for
  hardcoded-copy assertions before P4.
- **`publicResClass` DSL (P4).** The exact `compose.resources {}` accessor for
  Compose Multiplatform 1.10.3 must be verified at implementation time; if the
  generated `Res` is module-private, feature modules will not compile.

## Open questions to resolve in planning

1. P2: retune `titleSmall` weight to Medium (titles read straight off the slot),
   or leave it Bold and map the 14sp Medium uses to `bodyMedium`? (Leaning: retune
   `titleSmall` to Medium; it is the semantic slot for panel titles.)
2. P3: single `SkillBillDimens` object with tiered spacing, or split spacing vs.
   sizing into two objects? (Leaning: one `SkillBillDimens` for spacing/size
   primitives, keep structural sizes in `SkillBillMetrics`.)
3. P4: one `strings.xml` for all UI copy, or one per feature? (Leaning: single
   source of truth in designsystem; split only if it grows unwieldy.)

## Validation strategy

After each subtask, run `(cd runtime-kotlin && ./gradlew check)` (compiles with
`allWarningsAsErrors = true` and runs detekt + tests). Then run the grep gates
that prove each phase's completeness (shapes / type / dimens / strings). After
the final phase, smoke the app with `:runtime-desktop:run` through every dialog,
toolbar, command palette, code editor (read-only and editable), nav tree, and
inspector to confirm pixel layout is unchanged.

## References

- `.feature-specs/SKILL-49-material3-theme-adoption/spec.md` (foundational M3 work)
- `runtime-kotlin/runtime-desktop/core/designsystem/src/commonMain/kotlin/skillbill/desktop/core/designsystem/`
  (`SkillBillShape.kt`, `SkillBillTypography.kt`, `SkillBillMetrics.kt`, `SkillBillTheme.kt`)
- `runtime-kotlin/build-logic/convention/src/main/kotlin/KmpComposeConventionPlugin.kt`,
  `…/dev/skillbill/runtime/buildlogic/Kmp.kt` (`allWarningsAsErrors`, applied plugins)
- Audit date: 2026-06-28. Findings verified at the cited files.
