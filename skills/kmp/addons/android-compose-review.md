# KMP Android Compose Review Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the review scope clearly contains Android Compose UI.

This file is a review index for `bill-kmp-code-review` and `bill-kmp-code-review-ui`. It is not a standalone review command. The guidance adapts transferable Android/Compose review concerns from Google's public `android/skills` repository and keeps migration or upgrade workflows out of scope.

## Section index

Scan this file first. Then open only the linked topic files whose cues match the diff instead of loading all Android guidance by default.

- `## Activation signals`
  Read first to decide whether `android-compose` should be active.
- `[android-compose-edge-to-edge.md](android-compose-edge-to-edge.md)`
  Read when the diff touches `Scaffold`, app bars, lists, `WindowInsets`, system bars, IME behavior, activities, or `AndroidManifest.xml`.
- `[android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md)`
  Read when the diff touches list-detail, panes, rails, `NavigationSuiteScaffold`, or large-screen-specific Compose surfaces.
- `[android-navigation-review.md](android-navigation-review.md)`
  Read when the diff touches route models, deep links, scene destinations, or Android navigation ownership.
- `[android-design-system-review.md](android-design-system-review.md)`
  Read when the diff touches `MaterialTheme`, color schemes, typography, shapes, XML theme migration, or Android-specific styled component replacements.
- `[android-interop-review.md](android-interop-review.md)`
  Read when the diff mixes Compose with legacy Views or Fragments through `ComposeView`, `AndroidView`, `AndroidViewBinding`, or `AndroidFragment`.
- Generic Compose enforcement stays in `compose-guidelines.md`; use this add-on only for Android-specific review depth beyond that rubric.

## Activation signals

Select `android-compose` when the scoped diff includes:

- `@Composable` functions or screen composables
- Compose previews, `remember*`, `LaunchedEffect`, or other side-effect APIs
- `Scaffold`, `LazyColumn`, `LazyRow`, insets, or IME handling
- Android-specific Compose resources, edge-to-edge setup, or pane/adaptive layout wiring

## Review boundary

- Keep this add-on subordinate to the routed `kmp` review.
- Use it to extend the existing Compose review rubric with Android-specific Compose risks.
- Do not turn review comments into migration plans, AGP/Gradle/Kotlin upgrade advice, or product-specific Android rollout instructions.
