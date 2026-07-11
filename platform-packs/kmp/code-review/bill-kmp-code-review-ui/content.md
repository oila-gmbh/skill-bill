---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
internal-for: bill-code-review
---

# KMP UI Review Specialist

Review only UI state, rendering, framework usage, and interaction correctness.

## Focus

- Compose state ownership, recomposition, effects, navigation boundaries, previews, and loading/content/error/empty rendering
- Android and Compose Multiplatform API selection at the source-set boundary

## Ignore

- Accessibility-only findings that belong to the `ux-accessibility` specialist
- Escaping, secrets, or sensitive-data failures that belong to the Kotlin `security` baseline specialist

## Applicability

Use this specialist for `@Composable` functions, UI state holders, Compose navigation, platform UI interop, previews, and user-visible state transitions.

## Project-Specific Rules

### Governed Compose Rubric

- Read [compose-guidelines.md](compose-guidelines.md); an H2 section is insufficient for the detailed Compose rubric, and this companion is its single source of truth.
- Enforce every governed H2 rubric section against the applicable target: State Hoisting; Composable Function Signature Conventions; Recomposition & Performance; Theming & Design System; String Resources & Localization; Compose Multiplatform; Composable Structure & Decomposition; Side Effects; Navigation Integration; Preview Annotations; Error Handling & Loading States; Proper UI Elements; Modifier Best Practices; and ViewModel Integration.
- Do not duplicate that checklist here; require violations to identify the named rubric section, concrete failure precondition, and user-visible or runtime consequence.

### Add-On Boundaries

- Apply only generated add-ons selected by the baseline routing table, and verify Android-only add-ons are not enforced against `commonMain` or iOS-only code.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
