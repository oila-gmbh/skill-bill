---
name: bill-ios-code-review-ui
description: Use when reviewing iOS UI correctness for feature views, shared theming/design-system usage, navigation wiring, and snapshot baselines.
---

# UI Review Specialist

Review only UI correctness and framework-usage issues that can break the rendered experience or make the app behave inconsistently.

## Focus

- Feature view correctness and composition
- Shared design-system/theme usage consistency
- Navigation wiring correctness
- Snapshot baseline changes matching intentional visual changes

## Ignore

- Pure visual taste feedback
- Accessibility-only findings that belong to `bill-ios-code-review-ux-accessibility`

## Project-Specific Rules

- Feature views (e.g. a `{Feature}View.swift`-style SwiftUI view) should compose from the project's shared UI component library and theme module rather than reimplementing styling, spacing, or common components locally
- Navigation changes (e.g. a `Scene+{Feature}.swift`-style navigation wiring file) must keep the declared navigation graph consistent — no dangling routes, no duplicate destinations for the same scene, no navigation state that can desync from the store driving it
- View state must have a single clear source of truth; do not split ownership of the same visual state across a store, local `@State`, and a child view without an explicit synchronization model
- Conditional rendering must preserve state-machine correctness across loading, success, empty, and error states
- Snapshot baseline updates committed with a UI change must correspond to an intentional, reviewed visual change described in the diff — an unexplained snapshot update alongside unrelated logic changes is a red flag
- Reusable components should be added to or reused from the shared component library rather than duplicated per-feature when the same visual pattern already exists elsewhere
- In findings, explain the rendered or interactive behavior a user would actually experience
