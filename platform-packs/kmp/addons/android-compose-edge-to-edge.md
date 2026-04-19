# Android Compose Edge-To-Edge

Use this topic file only after `android-compose` has already been selected for routed `kmp` work.

Read this file when the diff touches `Scaffold`, app bars, `WindowInsets`, system bars, IME behavior, Android activities, or `AndroidManifest.xml`.

This guidance mainly adapts the official Android `system/edge-to-edge` skill into a governed KMP add-on shape.

## Source recipes

- Android `system/edge-to-edge` skill
  Read when the work introduces or audits edge-to-edge setup, safe-area handling, system bars, or IME resizing behavior.
- Focus on the transferable edge-to-edge and inset application patterns only.
- Exclude SDK bump, migration, and upgrade-workflow instructions from the source skill.

## Implementation guidance

- Prefer `enableEdgeToEdge()` before `setContent` in Android activities that own the Compose surface.
- Treat activity-level edge-to-edge enablement as part of the Android route boundary. A screen should not silently assume edge-to-edge if the host entry point still renders in legacy insets mode.
- Prefer `Scaffold` and pass `innerPadding` into content instead of padding an outer parent container.
- For scrollable content such as `LazyColumn` or `LazyRow`, feed inset padding into `contentPadding` and consume upstream inset values deliberately.
- When Material components already manage their own safe areas, pass insets to the component rather than padding the entire parent container.
- When you are outside a `Scaffold`, use `safeDrawingPadding()` or `windowInsetsPadding(WindowInsets.safeDrawing)` rather than inventing ad hoc spacing.
- For deeply nested Android Compose surfaces with repeated inset plumbing, use inset rulers or another central strategy instead of stacking unrelated paddings down the tree.
- Apply inset values once. Avoid stacking parent padding and child inset modifiers on the same axis unless you have a concrete reason.
- For adaptive scaffolds such as `NavigationSuiteScaffold`, apply insets to the inner screen content rather than the scaffold parent itself.
- When a decorative element needs to match a system bar, prefer inset size modifiers instead of hard-coded dimensions.
- Verify system-bar legibility as well as geometry. Edge-to-edge is not complete if content reaches the bar but the bar icons or background contrast are wrong.

## IME, focus, and scrolling

- On Android activities with soft input, prefer `android:windowSoftInputMode="adjustResize"` in the manifest when the screen relies on keyboard resizing.
- Keep the focused field reachable when the IME opens. Prefer patterns that consume upstream insets before adding IME-specific padding.
- If `imePadding()` is needed, apply it to the scrolling content container and verify the parent is not already providing IME insets through `contentWindowInsets`.
- When the UI scrolls to keep focus visible, ensure modifier order still lets the content resize before scroll behavior runs.
- Use `BringIntoViewRequester` or equivalent focus-driven scrolling only when the layout actually needs explicit focus correction.
- Verify multiline fields, bottom actions, and sticky submit buttons together. IME issues often hide one of these while the text field itself still appears correct.

## Review focus

- Verify inset handling is deliberate and applied once.
- Flag parent-level padding on app bars or shared containers when it prevents content or backgrounds from drawing correctly into system bar areas.
- Flag missing activity-level edge-to-edge enablement when the screen now assumes an edge-to-edge layout but the Android entry point was not updated.
- For adaptive scaffolds, flag parent-level safe-area padding on the scaffold itself when the content should instead own pane-specific inset handling.
- Check text-input screens for keyboard overlap risk and double-applied IME insets.
- Flag missing Android `adjustResize` setup when the layout depends on resize behavior and the activity boundary is in scope.

## Boundary

- Keep window/system-bar setup at the Android activity or route boundary, not inside reusable leaf composables.
- Do not use this topic file for generic Compose state-hoisting, theming, or side-effect rules already covered by `compose-guidelines.md`.
