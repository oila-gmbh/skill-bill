---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks to the pack-declared quality-check sidecar. Standalone and orchestrated invocations share one repair-window contract.

## Repair Window

Run the collect-all check once and read that output. Fix findings in batches in the same session: after each coherent batch (shared root cause or related group), re-run the same collect-all command to refresh the open set. Repeat check → fix-batch → check until green or you have used 3 full collect-all runs in the session (discovery and every refresh/confirm count). Do not invoke a full collect-all once per individual finding. Targeted compile, test, format, or analysis proofs are allowed between full collect-all runs. If the final collect-all still fails, its output is the new complete finding set.

## Pack validation_gate

When the dominant pack declares `validation_gate`, collect-all and confirmation are exactly that pack's `collect_all_full_gate_command` (and the pack's cache-bypassing collect-all when a cache-bypassing confirm is required). Do not rediscover a different full-suite command. When the pack declares no `validation_gate`, follow the routed sidecar's discovered entrypoint.

## Routing

Auto-route to the pack-declared quality-check skill for the dominant stack. Never name a stack-specific checker directly from this shell. Honor the routed sidecar Repair Window and Fix Strategy without reintroducing per-fix reruns here.
