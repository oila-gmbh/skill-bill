---
name: bill-typescript-code-review-testing
description: Use when reviewing TypeScript type checks, unit, integration, browser, contract, concurrency, and regression tests.
internal-for: bill-code-review
---

# Testing Review Specialist

## Focus

- Regression proof at public behavior, error, and state-transition boundaries
- Type-check, type-test, unit, integration, contract, browser, and end-to-end coverage
- Promise rejection, timers, cancellation, races, workers, and lifecycle determinism
- Node/browser environments, module formats, package entry points, and workspace packages

## Ignore

- Coverage percentage without missing behavior
- Tests that merely restate static types or implementation details
- Snapshot churn without a semantic assertion

## Project-Specific Rules

- Require the changed failure mode to fail before the fix and pass after it.
- Pair compile-time assertions with runtime tests when values cross JSON, JavaScript, database, network, or DOM seams.
- Assert observable output, state, errors, and side effects rather than only successful resolution.
- Await tested promises and make rejection expectations explicit so the runner cannot finish early.
- Use fake timers, controlled promises, barriers, and deterministic events instead of real sleeps and scheduler luck.
- Test null, undefined, malformed, extra-field, and version-skew inputs where narrowing or validation changed.
- Run meaningful tests in the actual Node, browser, worker, ESM, or CommonJS environment affected.
- Findings must name the unprotected regression and why existing coverage would miss it.
