---
name: bill-generic-code-review-testing
description: Technology-neutral testing review for behavioral coverage, failures, isolation, determinism, and regression value.
internal-for: bill-code-review
---

# Generic Testing Review

## Review Focus

Check that tests observe public behavior or durable effects, cover the changed failure and boundary conditions, and fail when the implementation regresses. Flag tautological assertions, mocks that replace the behavior under test, shared-state leakage, and timing-dependent expectations.

## Evidence

Tie every gap to a changed risk and describe the smallest test scenario that would distinguish correct from incorrect behavior.
