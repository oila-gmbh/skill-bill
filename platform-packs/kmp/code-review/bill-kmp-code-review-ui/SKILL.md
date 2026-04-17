---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
---

# KMP UI Best Practices

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kmp-code-review-ui` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Compose Review Rubric

The canonical KMP UI review command stays `bill-kmp-code-review-ui`. Governed add-ons apply only after the parent review has already routed to `kmp`.

When the parent KMP review selects the `android-compose` add-on, scan [android-compose-review.md](android-compose-review.md) first. If the add-on is split into topic files, open only the linked topic files whose cues match the diff, such as [android-compose-edge-to-edge.md](android-compose-edge-to-edge.md) and [android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md).

When the parent KMP review selects `android-navigation`, scan [android-navigation-review.md](android-navigation-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When the parent KMP review selects `android-interop`, scan [android-interop-review.md](android-interop-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When the parent KMP review selects `android-design-system`, scan [android-design-system-review.md](android-design-system-review.md) first and apply any Android-specific UI risks from it alongside the base Compose review rubric.

When no governed add-on applies, keep `Selected add-ons: none` and use the base Compose review rubric by itself.

For review enforcement, read [compose-guidelines.md](compose-guidelines.md) as the Compose review rubric covering:
state hoisting, signature conventions, recomposition & performance, theming, string resources, composable structure, side effects, navigation, previews, error/loading states, UI element selection, modifier best practices, and ViewModel integration.

Apply every section from `compose-guidelines.md` as a review checklist when reviewing `@Composable` code. Use the governed add-on only to extend the routed KMP review with transferable Android/Compose concerns; do not treat it as a standalone review command.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.

## Checklist

Before considering a composable done, verify:

- [ ] State is hoisted — composable is stateless with a stateful wrapper
- [ ] `modifier: Modifier = Modifier` on every public/internal composable below screen level
- [ ] `modifier` applied only to root element
- [ ] No hardcoded strings — all user-facing text uses `stringResource`
- [ ] No hardcoded colors, sizes, or spacing — uses theme tokens
- [ ] Stable types only — uses `@Immutable` / `ImmutableList` / primitives
- [ ] `collectAsStateWithLifecycle()` for flow collection
- [ ] `rememberSaveable` for state surviving config changes
- [ ] `LazyColumn` / `LazyRow` items have `key` and `contentType`
- [ ] Accessibility: all images/icons have appropriate `contentDescription`
- [ ] Side effects use correct API (`LaunchedEffect`, `DisposableEffect`, etc.)
- [ ] No `NavController` in screen composables — navigation via lambdas
- [ ] Preview annotations: light + dark mode minimum
- [ ] All states handled: loading, content, error, empty
- [ ] `Modifier.testTag` on key interactive elements
- [ ] No unnecessary decomposition — extractions have a reason
- [ ] File organization: screen → helpers → previews (top to bottom)

## Description
This content file is a platform-pack specialist area review module for
`bill-kmp-code-review-ui`. The baseline orchestrator delegates a single specialist area here.
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
