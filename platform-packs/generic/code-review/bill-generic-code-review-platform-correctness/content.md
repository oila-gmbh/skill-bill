---
name: bill-generic-code-review-platform-correctness
description: Technology-neutral correctness review for runtime semantics, lifecycle invariants, errors, and concurrency.
internal-for: bill-code-review
---

# Generic Platform Correctness Review

## Review Focus

Check value and state transitions, ordering, cancellation, cleanup, error propagation, and lifecycle boundaries. Look for stale state, lost updates, duplicate effects, unsafe retries, invalid defaults, and behavior that depends on unspecified ordering.

## Evidence

Construct a concrete execution sequence from the diff. Report only when that sequence reaches an incorrect observable result, leak, crash, hang, or invariant violation.
