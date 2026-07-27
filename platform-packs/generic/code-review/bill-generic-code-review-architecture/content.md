---
name: bill-generic-code-review-architecture
description: Technology-neutral architecture review for boundary ownership, dependency direction, state lifetime, and change isolation.
internal-for: bill-code-review
---

# Generic Architecture Review

## Review Focus

Trace responsibility across modules, processes, data owners, and external systems. Flag changes that create circular dependencies, duplicate sources of truth, bypass an owning boundary, or couple unrelated lifecycle and transaction concerns.

## Evidence

Verify the defect from changed call paths and repository contracts. State which boundary is violated, the reachable consequence, and the smallest correction that restores one owner without proposing an unrelated redesign.
