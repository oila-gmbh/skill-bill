---
name: bill-generic-code-review-platform-correctness
description: Technology-neutral correctness review for runtime semantics, lifecycle invariants, errors, and concurrency.
internal-for: bill-code-review
---

# Generic Platform Correctness Review

## Focus

Check value and state transitions, ordering, cancellation, cleanup, error propagation, and lifecycle boundaries. Look for stale state, lost updates, duplicate effects, unsafe retries, invalid defaults, and behavior that depends on unspecified ordering.

## Ignore

- Style preferences that do not violate runtime or lifecycle behavior.

## Applicability

Use when changes affect lifecycle, concurrency, state transitions, errors, or ordering.

## Project-Specific Rules

### Correctness Rules

- Require each changed `state transition` to preserve its documented invariant; reject races or ordering gaps that make an invalid state reachable.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
