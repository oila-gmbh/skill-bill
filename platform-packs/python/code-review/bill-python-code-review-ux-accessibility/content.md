---
name: bill-python-code-review-ux-accessibility
description: Review accessibility and UX of Python-rendered forms, templates, dashboards, validation feedback, keyboard flow, and localization-sensitive copy.
internal-for: bill-code-review
---

# Python UX Accessibility Review

Review whether Python-rendered interfaces support understanding, operation, recovery, and assistive-technology use.

## Focus

- Semantic structure, forms, errors, keyboard and focus behavior, assistive technology, dynamic fragments, localization, and truthful copy

## Ignore

- Defer non-accessibility UI behavior and rendering correctness to the `ui` specialist.
- Defer escaping, injection, and other trust-boundary findings to the `security` specialist.
- Pure visual taste that does not impair accessibility, understanding, recovery, or task completion

## Applicability

Use this specialist for server-rendered templates, Django forms, WTForms, admin flows, HTMX-style swaps, dashboards, generated reports, validation feedback, keyboard flows, and localization-sensitive UX.

## Project-Specific Rules

### Semantics and Forms

- Require headings, landmarks, table headers, form groups, status messages, accessible names, and alt text to convey structure without relying on visual layout alone.
- Require information, status, validation, and charts not to depend on color alone; preserve text, symbols, patterns, or programmatic state that communicates the same meaning.
- Require links for navigation and buttons or form submissions for destructive actions; reject destructive links whose semantics, keyboard behavior, or CSRF boundary misrepresents the operation.
- Require Django `Form` and WTForms fields to associate each label, help text, required state, field error, and error summary with the correct control and focus target.

### Dynamic Behavior and Copy

- Require modals and dialogs to expose their role, name, initial focus, focus containment, Escape or equivalent dismissal, and focus restoration without trapping users outside the active interaction.
- Require custom controls to provide native-equivalent keyboard operation and state, and require skip links or equivalent navigation affordances when repeated content would otherwise block efficient keyboard access.
- Verify focus moves to the new error, heading, dialog, or logical continuation after an HTMX-style swap without trapping or unexpectedly resetting keyboard users.
- Require dynamic fragments and status changes to expose appropriate screen-reader announcements while using ARIA only when native semantics cannot express the behavior.
- Reject copy that promises save, delivery, completion, deletion, or other success before the system can guarantee that outcome; require recovery guidance for partial or failed operations.
- Preserve input, actionable errors, keyboard order, localization-safe pluralization, date/time/number formatting, timezone clarity, and translatable text through error and recovery paths; reject user-facing text embedded in generated images or reports when it cannot be translated or exposed through an accessible text alternative.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
