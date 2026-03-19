---
name: bill-code-review-ux-accessibility
description: Review UX correctness and accessibility risks, delegating Compose-heavy checks to bill-code-review-compose-check.
---

# UX & Accessibility Review Specialist

Review only user-impacting UX/accessibility issues.

## Focus
- Broken/ambiguous UX states and recovery flows
- Accessibility semantics, labels, focus order, and keyboard/talkback usability
- Validation/error visibility and actionable feedback
- Read-only/editable behavior mismatches
- User-facing inconsistency with product intent

## Compose Delegation
- If Compose UI files are in scope, run `bill-code-review-compose-check` and merge relevant findings.

## Ignore
- Pure visual preference debates without usability impact

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

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

## Output Rules
- Report at most 7 findings.
- Include user-visible consequence for each finding.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
