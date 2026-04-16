# KMP Android Navigation Review Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the review scope clearly contains Android navigation state, route modeling, deep links, or scene-based Android navigation UI.

This file is a review index for `bill-kmp-code-review` and `bill-kmp-code-review-ui`. It is not a standalone review command.

## Section index

Scan this file first. This add-on is intentionally self-contained because the important Android navigation review guidance belongs together.

- `## Activation signals`
  Read first to decide whether `android-navigation` should be active.
- `## Review focus`
  Use when the diff changes Android navigation behavior.

## Activation signals

Select `android-navigation` when the scoped diff includes:

- route models, typed destinations, or Android navigation state containers
- `NavHost`, `NavDisplay`, `rememberNavBackStack`, or custom navigator abstractions
- deep links, back-stack reconstruction, `navigateUp`, or task-stack handling
- dialogs, bottom sheets, panes, list-detail navigation, or modular navigation wiring

## Review focus

- Flag Android surfaces with no clear back-stack owner.
- Flag non-persistable route identity where config/process restoration matters.
- Flag deep-link handling that bypasses the same routed state model used by in-app navigation.
- Flag multiple top-level destinations that discard route-local history unexpectedly.
- Flag conditional routing embedded in leaf UI instead of owned at a route boundary.
- Flag dialog, bottom-sheet, pane, or other scene destinations that bypass explicit navigation ownership.
- Flag untyped or string-based route construction when typed destination models are already available.
- Flag ViewModels that are not scoped per navigation entry when route identity should create distinct state.
- Flag modular navigation wiring that leaks host ownership into feature modules.
- Flag transition overrides that break pop or predictive-back behavior.
- Flag returned results that can replay after recomposition or restored state.

## Review boundary

- Keep this add-on subordinate to the routed `kmp` review.
- Use it to extend the existing KMP review with Android navigation risks.
- Do not turn review comments into dependency migration plans or Android-only setup instructions.
