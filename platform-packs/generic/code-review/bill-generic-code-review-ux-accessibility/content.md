---
name: bill-generic-code-review-ux-accessibility
description: Technology-neutral UX and accessibility review for task completion, semantics, input, focus, and localization.
internal-for: bill-code-review
---

# Generic UX and Accessibility Review

## Focus

Verify essential tasks work with keyboard or equivalent non-pointer input, meaningful names and roles, predictable focus, readable scaling, sufficient non-color cues, localized text, stable announcements, and recoverable errors.

## Ignore

- UI rendering correctness and security concerns owned by their respective specialists.

## Applicability

Use when changes affect task completion, semantics, keyboard input, focus, or localization.

## Project-Specific Rules

### UX and Accessibility Rules

- Require each changed `interaction` to expose semantic state and a complete keyboard path; reject focus loss or unlabeled controls that block the task.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
