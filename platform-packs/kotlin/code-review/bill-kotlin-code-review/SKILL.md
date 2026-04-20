---
name: bill-kotlin-code-review
description: Use when conducting a thorough Kotlin PR code review across shared, backend/server, or generic Kotlin code, or when providing the baseline Kotlin review layer for Android/KMP reviews. Select shared Kotlin specialists for architecture, correctness, security, performance, and testing, and add backend-focused specialists for API contracts, persistence, and reliability when server signals are present. Produces a structured review with risk register and prioritized action items. Use when user mentions Kotlin review, review Kotlin PR, Kotlin code review, or asks to review .kt files.
shell_contract_version: 1.1
template_version: 2026.04.19.5
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Description

Use when reviewing Kotlin changes across code-review specialists.

## Specialist Scope

This skill reviews Kotlin changes in one of two modes, chosen at runtime based on the pack's `declared_code_review_areas` in `platform.yaml`:

- **Delegated** — when areas are declared, route the diff to each specialist and aggregate findings. See the Delegated Mode section below.
- **Inline** — when no areas are declared, do the full review here in a single pass. See the Inline Mode section below.

Out of scope: quality-check work (lint, format, tests) and platform behavior governed by add-ons.

## Inputs

- PR branch and base branch, so the diff can be computed.
- Sibling supporting files: `stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`, `telemetry-contract.md`.
- Platform manifest `platform.yaml` for routing signals and declared specialists.

## Outputs Contract

Reports a Summary block followed by a Risk Register. The Summary exposes the shell-owned output identifiers so downstream triage and telemetry (owned by the shared router shell) can parse them:

- `Review session ID: <review-session-id>`
- `Review run ID: <review-run-id>`
- `Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>`
- `Applied learnings: none | <learning references>`

- Structured review with a risk register (CRITICAL / HIGH / MEDIUM / LOW).
- Delegated mode: per-specialist findings aggregated under area headings.
- Inline mode: findings grouped by concern (architecture, correctness, security, performance, testing).
- Prioritized action items.
- `Execution mode: inline | delegated` reported on its own line.

## Delegated Mode

Requires the owning pack's `declared_code_review_areas` list to be non-empty.

Applies when the diff is large, the risk profile is high, multiple areas are meaningfully involved, or the safest choice is unclear.

- Route the diff to each area's specialist using the `review-delegation.md` playbook.
- Pass each subagent its scoped file list, applicable active learnings, and the shared specialist contract.
- Aggregate specialist findings into the final risk register.
- Report `Execution mode: delegated`.

## Inline Mode

Applies in either of these cases:

- **Specialists declared, small and low-risk scope** — run each declared specialist sequentially in the current thread, read the specialist skill file as the primary rubric, keep findings attributed before merging.
- **No specialists declared** — review Kotlin directly here. Cover architecture, correctness, security, performance, and testing concerns in one pass.

Common to both:

- Apply the shared specialist contract in `review-orchestrator.md`.
- Merge and deduplicate findings into the final risk register.
- Report `Execution mode: inline`.

## Execution

Follow the instructions in [content.md](content.md).

## Execution Mode Reporting

When this code-review skill runs, report the execution mode on its own line:

```
Execution mode: inline | delegated
```

- `inline` — the current agent handled the work directly.
- `delegated` — the current agent dispatched the work to a specialist subagent or a sibling skill.

## Telemetry Ceremony Hooks

Follow the standalone-first telemetry contract documented in the sibling
`telemetry-contract.md` file:

- Emit a single `*_started` event at the top of the ceremony.
- Emit a single `*_finished` event at the bottom of the ceremony.
- Routers aggregate `child_steps` but never emit their own `*_started` or
  `*_finished` events.
- Degrade gracefully when telemetry is disabled: the skill must still run
  to completion without an MCP connection.
