---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks to the pack-declared quality-check sidecar. Standalone and orchestrated invocations share one repair-window contract.

## Repair Window

After a complete finding set exists from one gate or collect-all quality-check run, do not invoke any check command until every finding in that set is repaired at its root cause. While the set is open, forbidden work includes the full gate, collect-all gate, build-only gate, stack-specific checkers, `bill-code-check` re-invocation, format tasks, Gradle or module tasks (including `detekt`, `ktlintCheck`, `test`, and `compileKotlin`), and subagent-delegated checks. Allowed work is read, search, and source edits only. Verification runs once after the full set is repaired; if it fails, its output is the new complete finding set and a new repair window opens.

## Routing

Auto-route to the pack-declared quality-check skill for the dominant stack. Never name a stack-specific checker directly from this shell. Honor the routed sidecar Repair Window and Fix Strategy without reintroducing per-fix reruns here.
