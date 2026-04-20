---
name: bill-kotlin-code-review-reliability
description: Use when reviewing Kotlin backend/server reliability risks including timeouts, retries, background work, concurrency under load, caching, and observability-critical failures. Use when user mentions timeout, retry logic, circuit breaker, caching, or observability in Kotlin backend.
shell_contract_version: 1.1
template_version: 2026.04.19.5
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-reliability` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Description

Use when reviewing Kotlin changes for timeouts, retries, background work, and observability.

## Specialist Scope

This specialist covers timeouts, retries, background work, and observability in Kotlin changes.

Out of scope: other code-review areas, which are delegated to their own specialists declared under `declared_code_review_areas` in the owning `platform.yaml`.

## Inputs

- The slice of the diff relevant to this area.
- Sibling supporting files: `stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`, `telemetry-contract.md`.
- Platform manifest `platform.yaml` for routing signals.

## Outputs Contract

- Findings scoped to timeouts, retries, background work, and observability, each with severity and `file:line` location.
- No findings outside scope — unrelated issues belong in other specialists' output.
- `Execution mode: inline | delegated` reported on its own line.

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
