---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
---

# KMP UI Best Practices
## Compose Review Rubric

The canonical KMP UI review command stays `bill-kmp-code-review-ui`. Governed add-ons apply only after the parent review has already routed to `kmp`.

When no governed add-on applies, use the base Compose review rubric by itself.

For review enforcement, read [compose-guidelines.md](compose-guidelines.md) as the Compose review rubric covering:
state hoisting, signature conventions, recomposition & performance, theming, string resources, composable structure, side effects, navigation, previews, error/loading states, UI element selection, modifier best practices, and ViewModel integration.

Apply every section from `compose-guidelines.md` as a review checklist when reviewing `@Composable` code. Use the governed add-on only to extend the routed KMP review with transferable Android/Compose concerns; do not treat it as a standalone review command.

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
