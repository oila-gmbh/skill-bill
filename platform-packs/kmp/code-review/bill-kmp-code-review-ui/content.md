# KMP UI Best Practices
## Compose Review Rubric

The canonical KMP UI review command stays `bill-kmp-code-review-ui`. Governed add-ons apply only after the parent review has already routed to `kmp`.

When the parent KMP review selects the `android-compose` add-on, scan [android-compose-review.md](android-compose-review.md) first. If the add-on is split into topic files, open only the linked topic files whose cues match the diff, such as [android-compose-edge-to-edge.md](android-compose-edge-to-edge.md) and [android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md).

When the parent KMP review selects `android-navigation`, scan [android-navigation-review.md](android-navigation-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When the parent KMP review selects `android-interop`, scan [android-interop-review.md](android-interop-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When the parent KMP review selects `android-design-system`, scan [android-design-system-review.md](android-design-system-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When no governed add-on applies, keep `Selected add-ons: none` and use the base Compose review rubric by itself.

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
