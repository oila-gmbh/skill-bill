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

## Finding Requirements

- Report at most 7 findings.
- Include a user-visible consequence for each finding.
- Include a minimal, concrete fix.
