---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
---

## Descriptor

Governed skill: `bill-kmp-code-review-ui`
Family: `code-review`
Platform pack: `kmp` (KMP)
Area: `ui`
Description: Use when reviewing KMP changes for UI correctness and framework usage.

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

When review reporting applies, follow [review-orchestrator.md](review-orchestrator.md).

When telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).
