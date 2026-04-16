# KMP Android Design System Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the current work clearly touches Android Compose theming, design tokens, styled components, or XML-theme-to-Compose translation.

This file is an implementation index, not a standalone skill. It adapts the transferable Android design-system guidance for stack-owned KMP use without turning theming into a separate top-level package.

## Section index

Scan this file first.

- `## Activation signals`
  Read first to decide whether `android-design-system` should apply at all.
- `## Implementation guidance`
  Use when Android UI code changes theme ownership, tokens, or reusable styled components.
- `## Audit checklist`
  Use before introducing theme or component changes.
- `## Review focus`
  Use when validating Android design-system behavior.

## Activation signals

Activate `android-design-system` when the routed KMP work includes signals such as:

- `MaterialTheme`, color schemes, typography, shapes, or design tokens
- XML theme/style migration into Compose
- reusable styled components replacing view-era styles
- hybrid XML/Compose Android theming boundaries

## Implementation guidance

- Audit the Android UI infrastructure before adding theme code. Reuse the project’s existing Compose theme layer, spacing conventions, and reusable components instead of generating a parallel theme surface.
- Determine whether the Android app is XML-only, Compose-only, or hybrid in the touched boundary. Theme strategy must match that reality.
- Keep the existing XML theme as the source of truth when Compose is only being introduced incrementally. Do not invent a second Android brand palette just for the new Compose surface.
- Map old XML color names to Material or app-semantic roles rather than preserving hex-driven naming in Compose.
- Move reused color, typography, and shape values into a Compose theme layer instead of scattering them across leaf composables.
- Reuse existing strings, dimensions, drawables, and token resources when they already represent the Android product contract.
- When XML styles represent reusable component variants, create explicit styled composables or shared defaults instead of re-encoding style blobs inline at each call site.
- If the migrated Android UI is really a design-system component, create a reusable composable with a focused API instead of inlining the old style structure into one feature screen.
- Prefer project-native reusable components over generic Material defaults when the repo already has a branded Android component layer.
- When a token is missing, add it at the theme/design-system layer rather than hardcoding a one-off value into the feature composable.
- Keep migration scoped to the current Android surface. Do not rewrite the entire app theme when only one Compose route or screen is being introduced.

## Audit checklist

- Identify the existing Compose theme entry point, if any, before adding another.
- Locate XML theme resources that still own colors, dimensions, text appearances, or shapes used by the touched Android UI.
- Check whether the module uses Material 2, Material 3, or a custom design system so new Compose theme code matches the real host.
- Identify whether the old XML style encodes a reusable component role or just local layout styling.

## Review focus

- Flag new Android Compose theming code that ignores the project’s established theme layer or creates a duplicate `AppTheme` equivalent.
- Flag hardcoded colors, typography, or shape values that should come from the Android theme layer.
- Flag Android Compose surfaces that drift from the existing XML theme without an intentional product reason.
- Flag XML-style migration that reproduces view-era styling inline instead of introducing readable themed component APIs.
- Flag migrated components that lost resource-backed dimensions, typography, or shape constraints and replaced them with arbitrary constants.
- Check that theme changes respect hybrid XML/Compose ownership rather than assuming the View system can be deleted immediately.
- Check that light/dark schemes and component tokens remain consistent across old View and new Compose surfaces during incremental adoption.

## Implementation boundary

This add-on should enrich KMP implementation and review work only after `kmp` routing. It must not be treated as a new top-level package, slash command, or default workflow outside the owning stack.
