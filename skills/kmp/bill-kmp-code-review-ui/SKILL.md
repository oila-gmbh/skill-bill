---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
---

# KMP UI Best Practices

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kmp-code-review-ui` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Compose Guidelines

Read [compose-guidelines.md](compose-guidelines.md) for the full Compose review rubric covering:
state hoisting, signature conventions, recomposition & performance, theming, string resources, composable structure, side effects, navigation, previews, error/loading states, UI element selection, modifier best practices, and ViewModel integration.

Apply every section as a review checklist when reviewing `@Composable` code.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.

## Checklist

Before considering a composable done, verify:

- [ ] State is hoisted — composable is stateless with a stateful wrapper
- [ ] `modifier: Modifier = Modifier` on every public/internal composable below screen level
- [ ] `modifier` applied only to root element
- [ ] No hardcoded strings — all user-facing text uses `stringResource`
- [ ] No hardcoded colors, sizes, or spacing — uses theme tokens
- [ ] Stable types only — uses `@Immutable` / `ImmutableList` / primitives
- [ ] `collectAsStateWithLifecycle()` for flow collection
- [ ] `rememberSaveable` for state surviving config changes
- [ ] `LazyColumn` / `LazyRow` items have `key` and `contentType`
- [ ] Accessibility: all images/icons have appropriate `contentDescription`
- [ ] Side effects use correct API (`LaunchedEffect`, `DisposableEffect`, etc.)
- [ ] No `NavController` in screen composables — navigation via lambdas
- [ ] Preview annotations: light + dark mode minimum
- [ ] All states handled: loading, content, error, empty
- [ ] `Modifier.testTag` on key interactive elements
- [ ] No unnecessary decomposition — extractions have a reason
- [ ] File organization: screen → helpers → previews (top to bottom)
