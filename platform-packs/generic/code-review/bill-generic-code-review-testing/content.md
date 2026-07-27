---
name: bill-generic-code-review-testing
description: Technology-neutral testing review for behavioral coverage, failures, isolation, determinism, and regression value.
internal-for: bill-code-review
---

# Generic Testing Review

## Focus

Check that tests observe public behavior or durable effects, cover the changed failure and boundary conditions, and fail when the implementation regresses. Flag tautological assertions, mocks that replace the behavior under test, shared-state leakage, and timing-dependent expectations.

## Ignore

- Coverage-only assertions that do not establish behavior or a failure path.

## Applicability

Use when changes affect tests, fixtures, determinism, isolation, or regression evidence.

## Project-Specific Rules

### Testing Rules

- Require each changed `test contract` to observe the production effect it claims; reject tautological assertions at the test boundary that hide a failure or create false confidence.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
