---
name: bill-generic-code-review-performance
description: Technology-neutral performance review for amplification, blocking, resource lifetime, and scale.
internal-for: bill-code-review
---

# Generic Performance Review

## Focus

Inspect repeated work, unbounded collections, blocking on constrained workers, excessive serialization or transfer, resource retention, and algorithms whose cost grows unexpectedly with user or data volume.

## Ignore

- Micro-optimizations without a measurable or scale-sensitive consequence.

## Applicability

Use when changes affect hot paths, blocking work, allocation, batching, or resource lifetime.

## Project-Specific Rules

### Performance Rules

- Verify each changed `hot path` has bounded work; reject amplification or blocking that causes a measurable latency, memory, or throughput failure.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
