# Runtime desktop design system — history

## [2026-06-28] SKILL-96 m3-tokenization (dimensions subtask)
Areas: runtime-desktop/core/designsystem, runtime-desktop/feature/skillbill, runtime-desktop/core/ui
- Added `SkillBillDimens` — a direct-access `object` of spacing/padding/border/control/component sizing primitives beside `SkillBillMetrics`: border widths (`borderNone`/`hairline`/`divider`), a 4dp-grid spacing scale (`spacingXs`..`spacing5xl`), a parallel padding scale (`padXs`..`pad5xl`), off-grid half-values (`space3`/`space5`/`space7`/`space9`), control/row/icon heights, and component-specific sizes (checkbox, lineNumberWidth, dialog/command-palette/tab/chip widths). Extended `SkillBillMetrics` with structural bar heights (`editorCommandBarHeight`/`footerHeight`/`dialogHeaderHeight`), navigation-pane bounds + resize handle, the nav-tree indent base/step, and `toolbarMenuWidth` — keeping the original six fields verbatim.
- Migrated all 264 inline `.dp` literals across 16 commonMain UI files (feature/skillbill + core/ui) to tokens via a deterministic dp->token map (0dp transparent borders -> `borderNone`, 1dp borders -> `hairline`, 1.4dp stroke -> `divider`, padding -> `pad*`, gaps/spacers -> `spacing*`/`spaceN`, structural bars -> `SkillBillMetrics.*`); the nav-tree per-depth indent `(22 + depth*16).dp` is now `navTreeBaseIndent + navTreeDepthStep * depth` (Dp+Dp, operand order matters: `Dp.times(Int)` exists, `Int.times(Dp)` does not). The `.dp` grep gate is now empty in feature/ and core/ui.
- Removed `@file:Suppress("MagicNumber")` from every UI file (kept `FunctionName`/`LongMethod`); the suppression existed only to hide these literals. A few residual non-`.dp` magic numbers the suppression also hid were named as `private const val`s (toolbar `SIDE_PANEL_WIDTH_RATIO`, nav-tree `TREE_TEXT_ALPHA_*`, editor-tab title thresholds, inspector `GENERATED_LABEL_ALPHA_DISABLED`) so detekt stays clean. detekt's `ignoreNamedArgument` already exempts every `.copy(alpha = Xf)` call, so no alpha tokenization was needed (colors remain a non-goal).
- Reusable: feature UI must consume `SkillBillDimens.*` (spacing/padding/control/component sizing) or `SkillBillMetrics.*` (frame chrome: pane widths, bar heights) instead of inline `.dp`; `0.dp` transparent borders use `SkillBillDimens.borderNone`. Keep `SkillBillMetrics` for the app skeleton and `SkillBillDimens` for everything component-level.
- Reusable tests: `SkillBillThemeTokensTest` pins every `SkillBillDimens` and `SkillBillMetrics` value against its dp literal; `SkillBillFrameTokenWiringTest` adds a regression guard that walks the feature/core-ui commonMain trees and fails on any remaining inline `.dp` literal (value-pinning lives in the designsystem module so the pinning literals themselves stay inside the token-defining module, keeping the broad dp grep gate green outside designsystem).
- Known limitation: `./gradlew check` does not run `detektJvmMain`/`detektJvmTest` (the KMP jvm source sets carry pre-existing MagicNumber/InjectDispatcher/UnreachableCode debt that is outside this subtask's commonMain scope and does not gate `check`); the authoritative dimension gate is the commonMain `.dp` grep + the wiring test, both green.
Feature flag: N/A
Acceptance criteria: 6/6 — AC2 `.dp` grep empty in UI commonMain; AC3 no `MagicNumber` suppressions in UI; AC6 `(cd runtime-kotlin && ./gradlew check)` green.

## [2026-06-28] SKILL-96 m3-tokenization (typography AC4 font-weight fix)
Areas: runtime-desktop/core/designsystem
- Fixed the AC4 Medium→Normal regression surfaced at audit: set `fontWeight = FontWeight.Medium` on the five ambient-inheriting `SkillBillTypeStyles` tokens (`code`, `body125`, `body13`, `caption`, `codeCaption`) that previously had a null weight; `SkillBillThemeTokensTest` now asserts Medium on all five instead of `assertNull`.
- Root cause: passing an explicit `style=` to a Material3 `Text` REPLACES `LocalTextStyle.current` entirely and merges only per-call named params — the ambient (which resolves to `bodyLarge` = Medium) is NOT inherited. A token with `fontWeight = null` therefore resolves to Normal (W400) once it is passed as `style=`, silently regressing the ~28 regular-`Text` sites that previously had no explicit weight and rendered ambient Medium.
- Reusable: when authoring a `SkillBillTypeStyles` token consumed by `Text(style=…)`, bake the resolved weight (and family) into the token literal — never rely on `bodyLarge`-ambient inheritance through an explicit `style=`. The only token that may keep an explicit Normal is `bodySmallNormal` (12sp, intentionally Normal for the non-primary footer button).
- Reusable tests: `SkillBillThemeTokensTest` pins the resolved weight of every extended style, so a token-level weight regression now fails the build rather than passing as an ambient-resolution invisible defect.
Feature flag: N/A
Acceptance criteria: 6/6 — AC4 font-weight parity restored; `(cd runtime-kotlin && ./gradlew check)` green.

## [2026-06-28] SKILL-96 m3-tokenization (typography subtask)
Areas: runtime-desktop/core/designsystem, runtime-desktop/feature/skillbill, runtime-desktop/core/ui
- Added `SkillBillTypeStyles` — a 10-style direct-access `object` (code/body125/body13/mono13/semiBoldLabel/monoBadge/caption/codeCaption/microLabel/bodySmallNormal) in `skillbill.desktop.core.designsystem` beside `SkillBillMetrics`/`SkillBillShape`, holding the non-standard size+weight+family tuples; deliberately NOT routed through the `SkillBillTheme` CompositionLocal facade (matches the `SkillBillMetrics` convention).
- Retuned `titleSmall` in `SkillBillTypography.kt` from Bold to Medium/14sp; extended `SkillBillThemeTokensTest` to pin `titleSmall`/`bodySmall`/`bodyLarge`/`labelSmall` and all 10 extended styles.
- Migrated every inline `fontSize =`/`fontWeight =`/`TextStyle(` site across 13 commonMain UI files (feature/skillbill + core/ui) to `MaterialTheme.typography.*` or `SkillBillTypeStyles.*`; the `fontSize =`/`fontWeight =`/`TextStyle(` grep gate is now empty in feature/ and core/ui.
- Reusable: feature UI must consume `MaterialTheme.typography.*` or `SkillBillTypeStyles.*` instead of inline `fontSize=`/`fontWeight=`/`TextStyle(...)`; the only gate-exempt inline overrides are `.copy(fontFamily = FontFamily.Monospace)`, `.copy(lineHeight = …)`, and `.copy(color = …)`, and `letterSpacing = 0.sp` stays inline where the original deliberately overrode the ambient tracking.
- Reusable tests: `SkillBillThemeTokensTest` pins the typography slots and all 10 extended styles so future drift is caught by `:runtime-desktop:core:designsystem:jvmTest`.
- Known limitation: `gradlew check` is blind to composition-level (ambient-resolved) font weight — a green build does NOT prove rendered weights; when editing type tuples, bake size+weight explicitly into `SkillBillTypeStyles` (do not rely on `bodyLarge`-ambient inheritance) or add a compose test that reads the ambient-merged style.
- Deliberate change: `titleSmall` is now Medium/14sp, so any panel header or M3 component defaulting to `titleSmall` renders Medium rather than Bold — smoke-gaze those when re-theming.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-28] SKILL-96 m3-tokenization (shapes subtask)
Areas: runtime-desktop/core/designsystem, runtime-desktop/feature/skillbill
- Added `SkillBillShapeScheme` (5-tier: extraSmall 4dp, small 6dp, medium 8dp, large 12dp, extraLarge 16dp) and `SkillBillComponentShapes` (checkbox 2dp, chip 3dp, previewConsole 4dp, badge 5dp, control 6dp, panel 8dp, pill CircleShape); rebuilt `SkillBillShapes` from the scheme via the material3 5-param `Shapes` ctor.
- Migrated all 75 inline `RoundedCornerShape(N.dp)` + 3 `CircleShape` literals across 12 feature/skillbill UI files to tokens via a deterministic dp->token map (8dp containers -> `SkillBillTheme.shapes.medium`; component radii -> `SkillBillComponentShapes.*`); the `RoundedCornerShape(`/`CircleShape` grep gate is now empty in feature/ and core/ui.
- Reusable: feature UI must consume `SkillBillTheme.shapes.medium` for 8dp containers and `SkillBillComponentShapes.*` (checkbox/chip/previewConsole/badge/control/panel/pill) for component surfaces instead of inline shape literals.
- Reusable tests: `SkillBillThemeTokensTest` pins all 5 scheme tiers, full rebuild-from-scheme equality, and all 7 component shapes against their dp literals.
- Known limitation: `SkillBillShapes.large` moved 0dp -> 12dp and extraSmall(4)/extraLarge(16) became explicit; inert because no feature consumer reads raw `MaterialTheme` (all route via `SkillBillTheme.shapes`), though the one default-shape `AlertDialog` extraLarge moved 28dp -> 16dp per spec (confirmatory smoke only, not a blocking gate).
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-05-20] SKILL-49 material3-theme-adoption-foundation
Areas: runtime-desktop/core/designsystem, runtime-desktop/feature/skillbill, runtime-desktop app boundary
- Added distinct light/dark Material 3 schemes plus `SkillBillThemeTokens` for text fields, semantic tones, YAML syntax, and diff rendering; `SkillBillMaterialTheme(darkTheme=...)` now provides colors, tokens, and gradients from the same active theme boundary.
- Reusable: feature UI should consume `SkillBillTheme.textFieldTokens`, `semanticTones`, `syntaxTokens`, and `diffTokens` instead of local color palettes; feature-owned semantics such as unified-diff line classification stay outside core/designsystem and map to design-system tokens at the feature boundary.
- Reusable tests: `SkillBillThemeTokensTest` covers composition-local provisioning, light/dark scheme distinction, and contrast for semantic/text-field/syntax/diff token pairs; `SkillBillDesktopAppThemeBoundaryTest` verifies `SkillBillAppTheme` brace-range nesting around `SkillBillWindow` and routes.
- Review pitfall avoided: never pair light tokens with still-dark feature backgrounds; when a feature keeps a custom code/editor surface, expose a local theme-paired color bundle such as `codePaneColors()`.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
