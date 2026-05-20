# Runtime desktop design system — history

## [2026-05-20] SKILL-49 material3-theme-adoption-foundation
Areas: runtime-desktop/core/designsystem, runtime-desktop/feature/skillbill, runtime-desktop app boundary
- Added distinct light/dark Material 3 schemes plus `SkillBillThemeTokens` for text fields, semantic tones, YAML syntax, and diff rendering; `SkillBillMaterialTheme(darkTheme=...)` now provides colors, tokens, and gradients from the same active theme boundary.
- Reusable: feature UI should consume `SkillBillTheme.textFieldTokens`, `semanticTones`, `syntaxTokens`, and `diffTokens` instead of local color palettes; feature-owned semantics such as unified-diff line classification stay outside core/designsystem and map to design-system tokens at the feature boundary.
- Reusable tests: `SkillBillThemeTokensTest` covers composition-local provisioning, light/dark scheme distinction, and contrast for semantic/text-field/syntax/diff token pairs; `SkillBillDesktopAppThemeBoundaryTest` verifies `SkillBillAppTheme` brace-range nesting around `SkillBillWindow` and routes.
- Review pitfall avoided: never pair light tokens with still-dark feature backgrounds; when a feature keeps a custom code/editor surface, expose a local theme-paired color bundle such as `codePaneColors()`.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
