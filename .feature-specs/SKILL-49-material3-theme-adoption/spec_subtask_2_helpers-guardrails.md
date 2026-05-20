# SKILL-49 Material 3 Theme Adoption - Subtask 2: Pure Helpers and Guardrails

Parent spec: [spec.md](spec.md)

## Scope

Migrate pure rendering helpers and automated guardrails onto the design-system token surface created in subtask 1.

This subtask owns:

- Updating `YamlSyntaxHighlighter` so its public seam accepts named syntax tokens or a design-system-owned token value instead of feature-local `Color` parameters.
- Preserving existing YAML tokenizer behavior byte-for-byte; only the color source should change.
- Updating diff rendering helper seams that currently depend on local raw color values so they consume named design-system diff tokens.
- Adding tests or scanner coverage that fail when raw color usage is reintroduced outside `runtime-kotlin/runtime-desktop/core/designsystem`.
- Ensuring tests do not require feature UI files to import `androidx.compose.ui.graphics.Color`; design-system-owned fixtures are acceptable when they stay inside the design-system boundary.

## Acceptance Criteria

- AC4: No desktop source file outside core/designsystem contains raw `Color(0x...)`, `Color.Black`, `Color.White`, or `Color.Transparent`.
- AC5: No desktop source file outside core/designsystem imports `androidx.compose.ui.graphics.Color`.
- AC8: YAML syntax highlighting and diff rendering use named syntax/diff tokens.
- AC11: Tests fail if raw color usage is reintroduced outside the design-system module.

## Non-Goals

- Do not redesign syntax highlighting colors.
- Do not rewrite the YAML tokenizer.
- Do not migrate all visible feature UI surfaces; only pure helper seams and guardrails are in scope.
- Do not change runtime, scaffold, validation, render, install, or Git behavior.
- Do not introduce a feature flag; rollout is unflagged.

## Dependencies

Depends on subtask 1 because syntax and diff token APIs must exist before helper seams can consume them.

## Validation Strategy

Run `bill-quality-check`. Add or update focused tests under `runtime-kotlin/runtime-desktop/feature/skillbill/jvmTest` or an appropriate desktop test source set to prove:

- YAML text classification/output behavior remains unchanged.
- Highlighting and diff rendering resolve colors through named tokens.
- A raw-color scanner fails for raw `Color(0x...)`, `Color.Black`, `Color.White`, `Color.Transparent`, and `androidx.compose.ui.graphics.Color` imports outside `core/designsystem`.
- The scanner covers test sources as well as production sources, with only intentional design-system-owned fixtures allowed.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-49-material3-theme-adoption/spec_subtask_2_helpers-guardrails.md`.
