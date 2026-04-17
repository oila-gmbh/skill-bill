---
name: bill-kmp-code-review-ux-accessibility
description: Use when reviewing UX correctness and accessibility risks, delegating UI-framework-heavy checks to bill-kmp-code-review-ui. Use when user mentions UX review, accessibility, content description, screen reader, or localization review.
---

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

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.

## Description
This content file is a platform-pack specialist area review module for
`bill-kmp-code-review-ux-accessibility`. The baseline orchestrator delegates a single specialist area here.
The sections above define the specialist playbook; the sections below satisfy
the shell+content contract v1.0.

## Specialist Scope
Scoped to one approved code-review area. Does not cover other areas.

## Inputs
Review scope, changed files, detected stack signals, active learnings,
`review_session_id`, `review_run_id`, and the `orchestrated` flag.

## Outputs Contract
Findings in the shared Risk Register format
`- [F-###] <Severity> | <Confidence> | <file:line> | <description>`, plus
specialist-specific action items consumed by the baseline orchestrator.

## Execution Mode Reporting
Report `Execution mode: inline` or `Execution mode: delegated` per the
shell's output contract.

## Telemetry Ceremony Hooks
Specialist reviews never call `import_review` or `triage_findings` directly;
the baseline orchestrator owns lifecycle telemetry per
`telemetry-contract.md`.
