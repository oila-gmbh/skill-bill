---
name: bill-kmp-code-review-ux-accessibility
description: Use when reviewing UX correctness and accessibility risks, delegating Compose-heavy checks to bill-kmp-code-review-compose-check.
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
- If Compose UI files are in scope, run `bill-kmp-code-review-compose-check` and merge relevant findings.

## Ignore
- Pure visual preference debates without usability impact

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kmp-code-review-ux-accessibility` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

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
