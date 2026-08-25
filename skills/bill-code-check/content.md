---
name: bill-code-check
description: Dominant-stack quality-check entry point. Use when running checks, lint, format, or quality validation.
---

# Quality Check Router

## Purpose

Route dominant-stack quality checks to the pack-declared quality-check sidecar. Standalone and orchestrated invocations share one repair-window contract.

## Repair Window

Run the pack collect-all command, read the output, fix every finding, confirm with the same collect-all command. If the confirm fails, that output is the new finding set — loop until green. Do not re-run the full collect-all after each individual finding; targeted compile, test, and module checks are allowed only while repairing. Never suppress failures to pass.

## Pack validation_gate

When the dominant pack declares `validation_gate`, collect-all and confirmation are exactly that pack's `collect_all_full_gate_command` (and the pack's cache-bypassing collect-all when a cache-bypassing confirm is required). Do not rediscover a different full-suite command. When the pack declares no `validation_gate`, follow the routed sidecar's discovered entrypoint.

## Routing

Auto-route to the pack-declared quality-check skill for the dominant stack. Never name a stack-specific checker directly from this shell. Honor the routed sidecar Repair Window and Fix Strategy without reintroducing per-fix reruns here.
