---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks to the pack-declared quality-check sidecar. Standalone and orchestrated invocations share one repair-window contract.

## Repair Window

Run the collect-all check once and read that output. Fix every finding in the same session. Do not invoke the full gate, collect-all gate, or `bill-code-check` after each individual finding. Targeted compile, test, and module checks are allowed while repairing. When the set looks clean, run one confirmation check. If that fails, its output is the new complete finding set.

## Routing

Auto-route to the pack-declared quality-check skill for the dominant stack. Never name a stack-specific checker directly from this shell. Honor the routed sidecar Repair Window and Fix Strategy without reintroducing per-fix reruns here.
