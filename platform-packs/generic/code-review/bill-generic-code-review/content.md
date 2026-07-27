---
name: bill-generic-code-review
description: Use as the manifest-declared code-review fallback when no concrete platform pack owns the changed surface or concrete ownership remains ambiguous.
internal-for: bill-code-review
---

# Generic Code Review

Review the changed behavior without assuming a programming language, framework, operating system, or deployment model. Derive claims from the diff, repository contracts, tests, and user-visible consequences.

## Classification Rules

This pack is selected only by the fallback resolver. Its content and paths never establish stack ownership. Always include architecture and platform-correctness; add other specialists only when the changed behavior supplies evidence for their area.

## Review Method

Trace inputs through state changes and externally observable outputs. Check removed behavior as carefully as additions. Confirm boundary ownership, failure handling, compatibility, and regression evidence. Report only reachable defects with a concrete consequence and cite the smallest relevant changed location.

## Finding Discipline

Use the governed severity vocabulary and Risk Register format. Keep uncertainty explicit, avoid technology-specific assumptions, merge duplicate findings without losing evidence, and return an empty register when the diff supports no actionable finding.
