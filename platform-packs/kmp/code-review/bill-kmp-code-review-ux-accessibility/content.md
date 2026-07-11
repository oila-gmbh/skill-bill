---
name: bill-kmp-code-review-ux-accessibility
description: Use when reviewing UX correctness and accessibility risks, delegating UI-framework-heavy checks to bill-kmp-code-review-ui. Use when user mentions UX review, accessibility, content description, screen reader, or localization review.
internal-for: bill-code-review
---

# KMP UX & Accessibility Review Specialist

Review only user-impacting UX and accessibility failures.

## Focus

- Compose semantics, accessible names and roles, announcements, traversal, touch targets, font scaling, and localization-sensitive UX

## Ignore

- UI framework, rendering, preview, navigation, or loading/content/error/empty-state correctness owned by the `ui` specialist
- Escaping, secrets, or sensitive-data failures owned by the Kotlin `security` baseline specialist

## Applicability

Use this specialist when changed UI affects assistive technology, keyboard or focus behavior, touch interaction, localization, or task completion.

## Project-Specific Rules

### Compose Semantics

- Require `Modifier.semantics` where native semantics are insufficient; verify `mergeDescendants` preserves a coherent accessible control and reject merges that hide required child actions.
- Use `clearAndSetSemantics` only when replacing descendant meaning is intentional; reject it when it erases an accessible name, state, action, or error.
- Require decorative images to use `contentDescription = null`; require meaningful images and controls to expose localized accessible names.
- Require dynamic controls to expose accurate `stateDescription` and `Role`, and require urgent asynchronous updates to use an appropriate `liveRegion` without duplicate announcements.
- Require structural titles to expose `heading()` and validation failures to expose `error()` so assistive technology can navigate and announce them.

### Traversal, Targets, and Scaling

- Verify `traversalIndex` and `isTraversalGroup` produce a stable logical order; reject focus order that diverges from the task flow.
- Require `minimumInteractiveComponentSize` or an equivalent 48dp touch target for interactive controls; reject clipped or overlapping targets.
- Verify layouts survive increased `fontScale` without truncating actionable text, hiding controls, or blocking task completion.

### Localization Boundaries

- On Android targets, require user-facing text to use `stringResource(R.string.xxx)` and preserve needed `strings.xml` translations.
- In common Compose Multiplatform sources, require `Res.string` with `org.jetbrains.compose.resources.stringResource`; never require Android resource APIs in `commonMain`.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
