---
name: bill-kotlin-code-review
description: Use when conducting a thorough Kotlin PR code review across shared, backend/server, or generic Kotlin code, or when providing the baseline Kotlin review layer for Android/KMP reviews. Select shared Kotlin specialists for architecture, correctness, security, performance, and testing, and add backend-focused specialists for API contracts, persistence, and reliability when server signals are present. Produces a structured review with risk register and prioritized action items. Use when user mentions Kotlin review, review Kotlin PR, Kotlin code review, or asks to review .kt files.
---

## Descriptor

Governed skill: `bill-kotlin-code-review`
Family: `code-review`
Platform pack: `kotlin` (Kotlin)
Description: Use when reviewing Kotlin changes across code-review specialists.

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

Determine the review scope using [review-scope.md](review-scope.md).
Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

When stack routing applies, follow [stack-routing.md](stack-routing.md).

When delegated specialist review applies, use [specialist-contract.md](specialist-contract.md).

When delegated review execution applies, follow [review-delegation.md](review-delegation.md).

When review reporting applies, follow [review-orchestrator.md](review-orchestrator.md).

When telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).

`Review session ID: <review-session-id>`
`Review run ID: <review-run-id>`
`Applied learnings: none | <learning references>`
