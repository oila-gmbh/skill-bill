---
name: bill-kmp-code-review-ux-accessibility
description: Use when reviewing UX correctness and accessibility risks, delegating UI-framework-heavy checks to bill-kmp-code-review-ui. Use when user mentions UX review, accessibility, content description, screen reader, or localization review.
---

## Descriptor

Governed skill: `bill-kmp-code-review-ux-accessibility`
Family: `code-review`
Platform pack: `kmp` (KMP)
Area: `ux-accessibility`
Description: Use when reviewing UX correctness and accessibility risks, delegating UI-framework-heavy checks to bill-kmp-code-review-ui. Use when user mentions UX review, accessibility, content description, screen reader, or localization review.

## Execution

# UX & Accessibility Review Specialist

Review only user-impacting UX/accessibility issues.

## Focus
- Broken/ambiguous UX states and recovery flows
- Accessibility semantics, labels, focus order, and keyboard/talkback usability
- Validation/error visibility and actionable feedback
- Read-only/editable behavior mismatches
- User-facing inconsistency with product intent

## UI Delegation
- If KMP UI files are in scope, run `bill-kmp-code-review-ui` and merge relevant findings.

## Ignore
- Pure visual preference debates without usability impact
## Project-Specific Rules

### Localization
- All user-facing strings must use `stringResource(R.string.xxx)` — no hardcoded strings
- Never delete existing translations or `strings.xml` files
- Check for existing matching strings before creating new ones — reuse
- When removing UI components, verify orphaned string resources are cleaned up

### Previews
- Screens and components must have `@Preview` annotations
- Previews must use the project's theme composable

### Error States
- Screens must handle loading, content, error, and empty states
- Error messages come from UI (string resources), not ViewModel
- In findings, make the user-visible accessibility or UX consequence explicit.

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
