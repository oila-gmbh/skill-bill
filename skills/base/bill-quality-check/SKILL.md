---
name: bill-quality-check
description: Use when you want a generic quality-check entry point that detects the dominant stack in scope and delegates to the matching stack-specific quality-check skill. Use when user mentions run checks, validate, lint, format, quality check, or run quality.
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

```text
Routed to: <skill-name(s)>
Detected stack: <stack> | Mixed | Unknown/Unsupported
Signals: <markers>
Reason: <why this stack-specific quality-checker was selected>

<delegated quality-check output, or "No matching skill available yet" for unsupported>
```

## Telemetry

This skill is telemeterable via the `quality_check_started` and `quality_check_finished` MCP tools. This router does not emit telemetry on its own — routing metadata is carried in the concrete stack-specific skill's telemetry call.

**Standalone invocation** (user runs `bill-quality-check` directly):
1. Call `quality_check_started` once stack routing is decided, with `routed_skill`, `detected_stack`, `scope_type` (`files` / `working_tree` / `branch_diff` / `repo`), and `initial_failure_count` (0 if the first check has not run yet).
2. Save the returned `session_id`.
3. Call `quality_check_finished` when the quality-check loop finishes, with `session_id`, `final_failure_count`, `iterations`, `result` (`pass` / `fail` / `skipped` / `unsupported_stack`), optional `failing_check_names` and `unsupported_reason`.

**Orchestrated invocation** (invoked by another skill such as `bill-feature-implement` that passes `orchestrated=true`):
1. Skip `quality_check_started` entirely.
2. Call `quality_check_finished` once with `orchestrated=true` and all started+finished fields combined (`routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`, `final_failure_count`, `iterations`, `result`, `failing_check_names`, `unsupported_reason`, `duration_seconds`).
3. The tool returns `{"mode": "orchestrated", "telemetry_payload": {...}}`. Return that payload to the orchestrator — it will embed it in its own finished event.

The orchestrated flag must come from the caller. Never assume orchestrated mode from ambient state.
