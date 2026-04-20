---
name: bill-kotlin-quality-check
description: Run ./gradlew check and systematically fix all issues without using suppressions. Use when running Gradle checks, fixing lint errors, formatting issues, test failures, or deprecation warnings in Gradle/Kotlin projects. Fixes issues properly at the root cause instead of suppressing them. Use when user mentions gradlew check, Kotlin lint, ktfmt, detekt, or fix Gradle warnings.
shell_contract_version: 1.1
template_version: 2026.04.19.5
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Description

Use when validating Kotlin changes with the shared quality-check contract. Stack detection uses the sibling `stack-routing.md` playbook.

## Execution Steps

Stack detection uses the sibling `stack-routing.md` playbook. The per-pack execution steps live in the sibling `content.md`.

## Fix Strategy

Fix strategy is pack-owned. See the sibling `content.md` for the pack's priority order, never-suppress rules, and code style guidelines.

## Execution

Follow the instructions in [content.md](content.md).

## Execution Mode Reporting

When this quality-check skill runs, report the execution mode on its own line:

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
