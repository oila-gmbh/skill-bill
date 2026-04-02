---
name: bill-quality-check
description: Use when you want a generic quality-check entry point that detects the dominant stack in scope and delegates to the matching stack-specific quality-check skill.
---

# Shared Quality Check Router

Use this as the neutral validation entry point for feature workflows and standalone quality-check runs.

Keep this skill thin:
- detect the dominant stack in the current unit of work
- choose the matching stack-specific quality-check skill
- pass through the same scope and relevant context
- do not duplicate stack-specific build, lint, or fix heuristics here

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Setup

Determine the current unit of work:
- specific files
- working tree diff
- branch diff / PR scope
- repo-wide validation when the caller explicitly requests it

Inspect the changed files and repo markers before routing.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).

## Shared Stack Detection

Before routing, read [stack-routing.md](stack-routing.md). Use it as the source of truth for:
- stack taxonomy
- signal collection order
- dominant-stack tie-breakers
- mixed-stack routing rules

This supporting file lives beside `SKILL.md`; keep the routing rules in this skill aligned with it.

Do not redefine stack signals here unless a route-specific exception is truly unique to quality-check behavior.

## Delegation Rules

- If `kmp` signals dominate, delegate to the canonical quality-check implementation for the `kmp` package when it exists.
- If `backend-kotlin` signals dominate, delegate to the canonical quality-check implementation for the `backend-kotlin` package when it exists.
- If `kotlin` signals dominate, delegate to the canonical `bill-kotlin-quality-check` skill when it exists.
- If `agent-config` signals dominate, delegate to the canonical `bill-agent-config-quality-check` skill when it exists.
- If `php` signals dominate, delegate to the canonical `bill-php-quality-check` skill when it exists.
- If `go` signals dominate, delegate to the canonical `bill-go-quality-check` skill when it exists.
- Today, until separate `kmp` and `backend-kotlin` quality-check implementations exist, route `kmp`, `backend-kotlin`, and `kotlin` work to `bill-kotlin-quality-check`.
- If another supported stack dominates, delegate to that stack's canonical `bill-<stack>-quality-check` skill when it exists in the available skill catalog.
- If multiple supported stacks appear in one repo, run the matching stack-specific quality checks sequentially, not in parallel, so fixes stay deterministic.
- If the required stack-specific skill does not exist yet, say so explicitly and stop instead of pretending coverage exists.
- The delegated stack-specific skill is the source of truth for build commands, filtering rules, and fix strategy.

## Delegation Contract

When routing to another skill, pass along:
- the current unit of work and changed files
- the detected stack and key signals
- relevant `AGENTS.md` guidance and matching `.agents/skill-overrides.md` sections
- the rule that the delegated skill must follow its own `SKILL.md` as the primary rubric

## Output Format

For a single delegated skill:

```text
Routed to: <skill-name>
Detected stack: <stack>
Signals: <markers>
Reason: <why this stack-specific quality-checker was selected>

<delegated quality-check output>
```

For multiple delegated skills:

```text
Routed to: <skill-a>, <skill-b>
Detected stack: Mixed
Signals: <markers>
Reason: <why multiple stack-specific quality-checkers were selected>

<merged delegated quality-check output>
```

For unsupported stacks:

```text
Detected stack: Unknown/Unsupported
Signals: <markers>
Result: No matching stack-specific quality-check skill is available yet.
```
