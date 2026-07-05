---
name: bill-go-code-review-ux-accessibility
description: Use when reviewing Go product UX and accessibility risks including semantic structure, labels, keyboard behavior, focus management, validation feedback, and localization-sensitive UI copy.
internal-for: bill-code-review
---

# UX & Accessibility Review Specialist

Review only UX correctness and accessibility issues that can make the Go product harder to understand, harder to operate, or inaccessible to keyboard and assistive-technology users.

## Focus

- Semantic structure, labels, and screen-reader affordances
- Keyboard navigation, focus order, and focus restoration
- Validation feedback clarity and error-state discoverability
- Localization-sensitive UI behavior and user-facing copy regressions
- Interaction patterns where the UI technically renders but is confusing, misleading, or inaccessible

## Ignore

- Pure visual design preference
- Internal refactors with no user-visible outcome
- Security-only escaping issues that belong to `bill-go-code-review-security`

## Project-Specific Rules

- Every interactive control must have an accessible name that matches the intended user action
- Form inputs need explicit labels, correctly associated help text, and validation errors that are both visible and programmatically associated
- Do not rely on color alone to communicate status, errors, or destructive outcomes
- Keyboard users must be able to reach, operate, and dismiss interactive controls, modals, menus, and dialogs
- Focus should move intentionally after validation failures, modal open or close, and server-driven state changes that replace major content
- Dynamic server-rendered updates should preserve heading structure, landmarks, and screen-reader comprehension where the framework allows it
- Links and buttons must reflect their real behavior; do not use links for destructive state changes when the flow expects a button or form submission
- Empty, loading, success, and error states should be distinguishable and understandable without hidden context
- User-facing copy should stay consistent with the actual workflow outcome and should not imply success, permanence, or safety when the backend contract does not guarantee it
- Localization-sensitive paths should avoid hardcoded English-only assumptions for date, number, currency, directionality, and plural-sensitive messaging when the product supports multiple locales
- In findings, make the user-visible UX or accessibility consequence explicit
