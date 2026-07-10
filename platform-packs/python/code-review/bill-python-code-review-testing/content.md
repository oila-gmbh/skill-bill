---
name: bill-python-code-review-testing
description: Review Python pytest/unittest quality, fixtures, monkeypatching, parametrization, async/time tests, integration boundaries, and regression value.
internal-for: bill-code-review
---

# Python Testing Review

Review whether changed tests prove changed behavior and fail for the defect they claim to cover.

## Focus

- pytest and unittest structure, fixtures, monkeypatching, parametrization, async and time behavior, integration boundaries, negative paths, and regression value

## Ignore

- Test style or arrangement preferences that do not weaken regression detection, isolation, determinism, or contract coverage
- Missing tests for unreachable behavior or unchanged framework guarantees

## Applicability

Use this specialist whenever tests, fixtures, framework test clients, database or queue harnesses, async behavior, retries, time, filesystem boundaries, or bug-fix regression proof change.

## Project-Specific Rules

### Assertion Value and Contracts

- Apply the unit-test value lens: reject tests that only assert a mock was called, a response exists, a constant equals itself, a stub returns its configured value, or implementation details are reproduced in the expected value without proving observable behavior.
- Require every meaningful outcome to include negative-path assertions where applicable, including the exact status code and stable error shape rather than only a truthy response or exception type.
- Require changed bug fixes to reproduce the failing case and cover relevant serialization, time, nullability, permission, cleanup, and partial-failure boundaries.
- Verify fixture scope, parametrization, marks, environment cleanup, global state, and monkeypatch target ownership preserve isolation between tests.

### Async and Integration Determinism

- Require deterministic tests for retry, idempotency, replay, and duplicate delivery, including assertions that effects occur exactly once under repeated execution.
- Require time-sensitive tests to freeze or inject the clock with an explicit timezone, and cover cancellation and timeout paths without relying on local-zone assumptions or wall-clock timing.
- Verify `pytest-asyncio` mode and fixture loop scope match the repository configuration; reject async fixtures or tests that silently skip, bind resources to the wrong loop, or leak tasks across cases.
- Reject real sleeps, wall-clock races, unordered concurrency assertions, and mocks that bypass the database, client, queue, filesystem, or framework behavior under review.
- Require integration cleanup for transactions, sessions, external clients, temporary directories, queues, and background tasks on both success and error paths.
- Recommend `bill-unit-test-value-check` when the diff is test-only or new assertions appear tautological so coverage-only tests receive the dedicated value audit.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
