# KMP Android Navigation Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the current work clearly touches Android navigation state, route modeling, deep links, or scene-based Android navigation UI.

This file is an implementation index, not a standalone skill. It adapts the transferable Android Navigation 3 guidance for stack-owned KMP use without turning navigation into a separate top-level package.

## Section index

Scan this file first. This add-on is intentionally self-contained because the important Android navigation guidance belongs together.

- `## Activation signals`
  Read first to decide whether `android-navigation` should apply at all.
- `## Source recipes`
  Use to map the current work to the relevant Android navigation patterns.
- `## Implementation guidance`
  Use when the work changes route ownership, back stacks, deep links, scene destinations, or modular navigation wiring.
- `## Review focus`
  Use when validating Android navigation behavior.

## Activation signals

Activate `android-navigation` when the routed KMP work includes signals such as:

- `NavHost`, `NavDisplay`, `NavController`, route models, or typed destinations
- `rememberNavBackStack`, saveable back-stack ownership, or top-level route stacks
- deep links, `Intent.ACTION_VIEW`, synthetic back stacks, or `navigateUp`
- dialogs, bottom sheets, panes, scenes, or multi-back-stack Android navigation UI
- ViewModel route argument extraction or module-scoped navigator wiring

## Source recipes

- Navigation 3 `basic`, `basicdsl`, and `basicsaveable`
  Read when the diff introduces or rewrites core back-stack ownership and saved navigation state.
- Navigation 3 `common-ui`
  Read when top-level app chrome such as bottom bars or rails participates in top-level route switching.
- Navigation 3 `multiple-backstacks`
  Read when top-level tabs, bars, or rails each need their own preserved back stack.
- Navigation 3 `deeplinks-basic` and `deeplinks-advanced`
  Read when URLs or intents must resolve into the same routed state used by in-app navigation.
- Navigation 3 `conditional`
  Read when auth, onboarding, or first-run state switches users between navigation flows.
- Navigation 3 `dialog` and `bottomsheet`
  Read when destinations render as dialogs, sheets, or scene-managed transient UI rather than as ordinary full-screen routes.
- Navigation 3 `scenes-listdetail`, `scenes-twopane`, `material-listdetail`, and `material-supportingpane`
  Read when adaptive navigation surfaces coordinate panes, list-detail layouts, or supporting panes.
- Navigation 3 `animations`
  Read when the flow customizes forward, pop, or predictive-back transitions.
- Navigation 3 `passingarguments` and `type-safe-destinations`
  Read when destination keys, arguments, or ViewModel scoping are being refactored.
- Navigation 3 `modular-hilt` and `modular-koin`
  Read when navigation ownership spans feature modules or DI containers.
- Navigation 3 `results-event` and `results-state`
  Read when dialogs, sheets, editors, or subflows return data to earlier content.
- Keep only the transferable route/state/result patterns; do not import Android-only migration setup or dependency instructions.

## Implementation guidance

- Prefer a single navigation state owner per Android surface. Even when the UI is Compose-heavy, route switching, deep-link entry, and top-level stack policy should still be coordinated from one obvious place.
- Use serializable or otherwise persistable route keys when the Android flow needs to survive configuration change or process death. Do not keep route identity only in ephemeral composable state.
- Prefer the `entryProvider` DSL or another typed registry over large ad hoc `when` blocks once the navigation surface grows beyond trivial size.
- Keep navigation wiring above leaf composables. Reusable UI should still receive lambdas or abstract navigation actions, not Android navigation objects.
- Prefer type-safe destination models or typed route state over stringly typed route construction when the stack supports it.
- For top-level destinations, preserve route-local history explicitly. A bottom bar or rail should not wipe the user’s sub-navigation state on every tab change unless that reset is an intentional product rule.
- When the UI has multiple top-level destinations, preserve a distinct back stack per top-level route instead of rebuilding state on every tab switch.
- Treat deep links as navigation state construction, not as a special UI branch. Parse the intent into the same destination model the rest of the app uses.
- When deep links enter the app below the natural start destination, build the back path users expect for Back and Up instead of dropping them into an isolated leaf route with no navigation story.
- For auth, onboarding, or conditional flows, switch between navigation flows at a clear route boundary instead of hiding the condition inside a leaf screen.
- Persist the navigation state that users expect to survive configuration change or process death. Do not keep crucial back stack state only in transient composable memory.
- Keep navigation argument parsing close to the route boundary.
- If route keys feed a ViewModel, scope that ViewModel to the navigation entry so distinct route instances do not silently share state.
- When using dialogs, bottom sheets, or custom scenes as destinations, keep their route ownership and dismissal behavior inside the navigation layer rather than ad hoc local state.
- Treat dialog, bottom-sheet, and pane scenes as real navigation destinations with explicit metadata and back behavior, not as UI overlays that bypass routing.
- Keep route-level transitions consistent. If one destination overrides forward/pop transitions, verify predictive back and sibling destinations still produce a coherent movement language.
- If navigation crosses modules, keep route definitions, entry registration, and DI wiring decoupled enough that feature modules can own their own destinations without leaking host-layer details everywhere.

## Architecture notes

- If the Android app uses a custom navigator abstraction, keep the abstraction honest: it should still model back stack mutation, restricted-route redirects, and deep-link entry explicitly rather than hiding those behaviors behind generic `navigate()` calls.
- For conditional routes, carry the intended target through the login or gate flow so successful completion resumes the original navigation intent.
- For modular navigation, keep DI-scoped navigator state aligned with the Android lifecycle that owns the route set. Do not let an activity-retained or feature-retained navigator accidentally outlive the route assumptions it was built for.

## Review focus

- Check that the Android surface has one clear back-stack owner instead of a mix of local list state, nav state, and ad hoc booleans.
- Flag flows that should be saveable across config/process recreation but still use non-persistable route identity.
- Check that deep links build the same routed state the in-app flow uses instead of bypassing the normal navigation model.
- Flag multiple-top-level-route UIs that lose per-tab back stack state on tab changes.
- Flag auth/onboarding conditions embedded inside leaf UI when they should switch at a higher navigation boundary.
- Flag deep-link entry that omits the synthetic back stack users need for sane Back or Up behavior.
- Flag string-based route construction or argument extraction when the surrounding stack already supports typed destination models.
- Flag ViewModels that are not scoped per navigation entry when different route instances should produce distinct state.
- Check dialog, bottom-sheet, and other scene destinations for dismissal or back behavior that escapes the navigation model.
- Check modular navigation setups for host-feature coupling that makes route ownership unclear.
- Check custom transition overrides for inconsistency with predictive back or pop behavior.
- Check returned results from dialogs, bottom sheets, or editor flows for replay risk after recomposition or state restoration.

## Implementation boundary

This add-on should enrich KMP implementation and review work only after `kmp` routing. It must not be treated as a new top-level package, slash command, or default workflow outside the owning stack.
