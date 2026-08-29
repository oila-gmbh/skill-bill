---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks to the pack-declared quality-check sidecar. Standalone and orchestrated invocations share one repair-window contract.

## Repair Window

Run the collect-all check once and read that output. Fix every finding in the same session. Do not invoke the full gate, collect-all gate, `bill-code-check`, or any targeted compile, test, format, or analysis proof after each individual finding or between findings. When the set looks clean, run one confirmation check. If that fails, its output is the new complete finding set.

## Pack validation_gate

When the dominant pack declares `validation_gate`, collect-all and confirmation are exactly that pack's `collect_all_full_gate_command` (and the pack's cache-bypassing collect-all when a cache-bypassing confirm is required). Do not rediscover a different full-suite command. When the pack declares no `validation_gate`, follow the routed sidecar's discovered entrypoint.

## Routing

Auto-route to the pack-declared quality-check skill for the dominant stack. Never name a stack-specific checker directly from this shell. Honor the routed sidecar Repair Window and Fix Strategy without reintroducing per-fix reruns here.
