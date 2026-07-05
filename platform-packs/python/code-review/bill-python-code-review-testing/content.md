---
name: bill-python-code-review-testing
description: Review Python pytest/unittest quality, fixtures, monkeypatching, parametrization, async/time tests, integration boundaries, and regression value.
internal-for: bill-code-review
---

# Python Testing Review

Focus on whether the changed tests prove the changed behavior.

## Review Focus

- pytest and unittest structure: test names, arrangement, assertions, fixture scope, parametrization, marks, and isolation between tests.
- Fixtures and monkeypatching: overbroad fixtures, leaked environment variables, global state, patched imports at the wrong location, and mocks that bypass the behavior under review.
- Async and time-sensitive tests: event-loop use, task cleanup, sleeps, time freezing, timezone assumptions, retries, and cancellation paths.
- Integration boundaries: database/session lifecycle, transactions, external clients, filesystem temp dirs, queues, and framework test clients.
- Assertion value: tests should fail for the defect they claim to cover, not only assert that a function was called or that a response object exists.
- Regression coverage: changed bug fixes should include the failing case, edge cases for serialization/time/nullability, and negative paths where behavior matters.

## Findings Standard

Flag low-value tests, brittle tests, hidden integration leaks, or missing regression proof. Recommend `bill-unit-test-value-check` when the diff is test-only or the new assertions appear tautological.
