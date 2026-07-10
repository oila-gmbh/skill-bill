---
name: bill-rust-code-review-testing
description: Use when reviewing Rust regression coverage, unit and integration tests, doc tests, feature matrices, async behavior, concurrency, and test value.
internal-for: bill-code-review
---

# Rust Testing Review

Review whether tests prove changed behavior and failure modes.

## Checks

- Match tests to the public contract, regression, and realistic boundary rather than implementation details.
- Cover success, `Result` error variants, panic policy, invalid input, empty/boundary values, cleanup, and partial failure.
- Exercise ownership-sensitive APIs, concurrency interleavings, cancellation, timeouts, retries, and task shutdown deterministically.
- Include meaningful unit, integration, doc, property/fuzz, compile-fail, snapshot, or end-to-end tests when their risk surface applies.
- Verify important default, no-default, all-feature, and supported feature combinations without demanding a combinatorial matrix.
- Check target- or platform-gated code and workspace members affected by the change.
- Prefer controlled clocks, deterministic executors, bounded waits, and observable synchronization over sleeps.
- Reject tautological assertions, tests that merely restate mocks, ignored failures, and coverage-only branches.
- Ensure fixtures and golden outputs represent contracts and fail clearly when behavior regresses.

Do not require tests for trivial mechanical changes when existing checks prove the behavior.
