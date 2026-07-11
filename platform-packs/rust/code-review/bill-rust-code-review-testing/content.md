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

- Require a focused `#[test]` or `#[tokio::test]` that fails on the changed behavior before the fix; reject regression coverage that never exercises the failure path.
- Ensure unit tests assert observable `Result`, state transition, and side effects rather than private call order; flag tautological checks that permit incorrect behavior.
- Require integration tests under `tests/` when crate boundaries, executable behavior, database clients, or network adapters changed; reject mocks that conceal wire or lifecycle failures.
- Verify public examples and doctests with `cargo test --doc` when documentation is executable contract; flag stale code that breaks users copying the documented path.
- Require compile-pass or `trybuild` compile-fail cases when trait bounds, macros, lifetimes, or feature-gated public types changed; reject untested caller compilation regressions.
- Use `proptest` or `quickcheck` for parsers and invariant-heavy transformations when configured or justified by changed input risk; flag example-only coverage that misses invalid data classes.
- Extend an existing `cargo fuzz` target for changed untrusted parsing when the repository already maintains fuzz infrastructure; reject making fuzzing a universal prerequisite when it is not configured.
- Run configured `cargo miri test` for changed unsafe aliasing, initialization, or pointer ownership; flag memory-safety risk that ordinary tests cannot observe, without requiring Miri for unrelated code.
- Use an existing `loom::model` test when changed atomics, locks, or channels depend on adversarial interleavings; reject scheduler-luck coverage for a concurrency race.
- Avoid real sleeps by using `tokio::time::pause`, barriers, controlled channels, and bounded deadlines where supported; flag flaky timeout tests or hidden deadlocks.
- Require cancellation and shutdown tests to drop futures, close receivers, join workers, and inspect durable state when those paths changed; reject leaked tasks or truncated cleanup.
- Verify representative `cargo test --no-default-features`, named-feature, target, and workspace-member combinations from repository policy; reject invalid builds while avoiding mutually exclusive all-feature combinations.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
