---
name: bill-rust-code-review-testing
description: Use when reviewing Rust unit, integration, doc, property, fuzz, compile-fail, concurrency, and Cargo feature-matrix tests.
internal-for: bill-code-review
---

# Testing Review Specialist

## Focus

- Regression proof at public behavior, error, and state-transition boundaries
- Unit, integration, doc, property, fuzz, compile-fail, and snapshot test value
- Async, concurrent, timing, cancellation, and shutdown determinism
- Workspace members, targets, platforms, and Cargo feature combinations

## Ignore

- Coverage percentage without missing behavior
- Tests that merely restate type-system guarantees or implementation details
- Calls for exhaustive combinations when a justified representative matrix exists

## Applicability

Apply when changed Rust behavior, contracts, feature combinations, concurrency, or failure paths require regression proof.

## Project-Specific Rules

### Rust Testing Rules

- Verify `Rust test and fixture APIs` preserve their documented invariants; reject an undetected regression or false-positive test failure.
- Require the changed failure mode to fail before the fix and pass after it.
- Assert observable results, durable state, errors, and side effects rather than only successful execution.
- Avoid real sleeps and scheduler luck; use paused time, barriers, controlled channels, and bounded timeouts where supported.
- Test cancellation, dropped receivers, task failure, poisoned or contended state, and cleanup when those paths changed.
- Cover relevant `--no-default-features`, `--all-features`, named-feature, target, and workspace-member combinations.
- Use property tests or fuzzing for parsers and invariant-heavy transformations when examples cannot cover the risk surface.
- Keep fixtures explicit and prevent snapshots from hiding semantic regressions behind bulk updates.
- Findings must name the unprotected regression and use only the shared Risk Register and canonical severities.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
