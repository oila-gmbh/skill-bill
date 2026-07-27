---
name: bill-generic-code-review-architecture
description: Technology-neutral architecture review for boundary ownership, dependency direction, state lifetime, and change isolation.
internal-for: bill-code-review
---

# Generic Architecture Review

## Focus

Trace responsibility across modules, processes, data owners, and external systems. Flag changes that create circular dependencies, duplicate sources of truth, bypass an owning boundary, or couple unrelated lifecycle and transaction concerns.

## Ignore

- Naming or layout preferences without a reachable architectural failure.

## Applicability

Use when changes affect responsibility, dependencies, lifecycle ownership, or boundaries.

## Project-Specific Rules

### Boundary Rules

- Require each changed `boundary` to retain one explicit owner; reject cycles or bypasses that create competing authority and observable state divergence.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
