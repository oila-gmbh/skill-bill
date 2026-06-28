# Runtime desktop design system — history

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
