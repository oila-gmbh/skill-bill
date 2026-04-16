# KMP Android Interop Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the current work clearly mixes Android Compose with legacy Views, Fragments, or framework-owned host boundaries.

This file is an implementation index, not a standalone skill. It adapts the transferable Android interoperability guidance for stack-owned KMP use without turning interop into a separate top-level package.

## Section index

Scan this file first.

- `## Activation signals`
  Read first to decide whether `android-interop` should apply at all.
- `## Implementation guidance`
  Use when Compose mixes with Views, Fragments, or Android framework callbacks.
- `## Review focus`
  Use when validating Android host-boundary behavior.

## Activation signals

Activate `android-interop` when the routed KMP work includes signals such as:

- `ComposeView`, `AndroidView`, `AndroidViewBinding`, or `AndroidFragment`
- Compose hosted inside an existing View/Fragment screen
- legacy Android Views, Fragments, ads, maps, or custom Android widgets embedded in Compose
- broadcast receivers, `LocalContext`, `LocalView`, or other explicit framework-boundary Compose integrations

## Implementation guidance

- Keep the Android interoperability boundary explicit. Treat `ComposeView`, `AndroidView`, and `AndroidFragment` as route or host-level integration points, not as something every leaf composable should know about.
- Prefer full `setContent` ownership for fully Compose Android screens. Use `ComposeView` only when the screen is still hosted by View infrastructure.
- When adding Compose into an existing View screen, keep the host Activity or Fragment responsible for wiring lifecycle, saved state, and platform services instead of smuggling those concerns into child composables.
- When embedding Views in Compose, keep data flow one-way through the `update` block and clear or reset reused Views deliberately in lazy containers.
- Construct Views inside the `AndroidView` factory rather than holding raw View instances in `remember`.
- Use `AndroidViewBinding` when the Android UI being embedded is still naturally owned by an XML layout and view binding is already part of the host module.
- If `AndroidView` participates in a lazy list or pager, use the reuse-aware overload and implement `onReset` so recycled View instances do not leak stale state.
- When embedding Fragments in Compose, keep fragment arguments and state explicit and let `AndroidFragment` own fragment lifecycle cleanup.
- Avoid teaching leaf Compose UI to reach back into Fragment or View internals when a host-level callback or state adapter can own the bridge.
- When Compose needs Android framework objects, use the appropriate locals and side-effect APIs rather than ad hoc global lookups.
- If a composable registers Android framework listeners such as broadcast receivers, pair them with lifecycle-aware side effects and updated callback capture.
- Remove interop layers only after the remaining View usage actually disappears; do not strand hidden XML/View dependencies behind a seemingly pure Compose API.

## Boundary patterns

- Compose in Views: use when the Android host is still View-based but the feature body is ready to be Compose.
- Views in Compose: use when Android-only widgets, ads, maps, or custom Views still need to live inside a Compose screen.
- Fragments in Compose: use only when fragment ownership is still required by the Android boundary; do not keep fragments around as a default composition primitive.

## Review focus

- Flag Compose/View interop that leaks lifecycle or host concerns into reusable leaf composables.
- Flag `AndroidView` usage in lazy lists when the code ignores View reset/reuse behavior.
- Flag raw View construction outside the `AndroidView` factory when it breaks recomposition or lifecycle expectations.
- Flag embedded Fragments or Views that still own business state the Compose tree also tries to own.
- Flag mixed Compose/View surfaces that duplicate state on both sides of the bridge instead of choosing one source of truth.
- Check framework integrations such as receivers or service lookups for missing `DisposableEffect`/`rememberUpdatedState` style lifecycle handling.
- Check that Fragment or View wrappers have a clear removal path rather than becoming permanent unowned infrastructure.

## Implementation boundary

This add-on should enrich KMP implementation and review work only after `kmp` routing. It must not be treated as a new top-level package, slash command, or default workflow outside the owning stack.
