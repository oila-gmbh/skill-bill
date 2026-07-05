---
name: bill-ios-code-review-testing
description: Use when reviewing iOS test coverage quality, XCTest/snapshot-testing conventions, and the 1:1 SQL-statement-test cross-reference.
internal-for: bill-code-review
---

# Testing Review Specialist

Review only test-coverage and test-quality issues with real regression-protection impact.

## Focus

- Missing or weak test coverage for new views, stores, and SQL statements
- XCTest and snapshot-testing convention consistency
- 1:1 coverage between hand-written SQL statements and their statement tests
- Tautological or coverage-padding tests that do not validate real behavior

## Ignore

- Style-only test-naming preferences with no coverage impact

## Applicability

Use this specialist wherever test files change, or wherever new views, stores, or SQL statements ship without an accompanying test.

## Project-Specific Rules

- New or changed views should have a snapshot test (e.g. via `assertSnapshotsOf` or an equivalent snapshot-testing convention) covering their meaningful visual states, unless the project explicitly excludes that view type
- New or changed stores/reducers should have XCTest coverage for their action-handling behavior, not just their initial state
- A `{Statement}SQLStatementTests.swift`-style test per hand-written SQL statement is a recommended convention, not a universal hard rule — verify the repo applies it broadly before treating its absence as a violation (only a subset of statements carry one in practice). Flag a missing statement test as an at-most-Minor coverage gap, reserving higher severity for statements whose correctness is load-bearing; do not frame it as a mandatory "1:1" requirement the change breaks
- Snapshot baselines committed alongside a UI change must correspond to an intentional, reviewed visual change — a snapshot update with no visible reason in the diff is a red flag worth calling out
- Flag tests that assert only on mocks calling through to other mocks, or that would pass unchanged if the underlying behavior were broken (tautological or coverage-padding tests)
- Coverage disabled, weakened, or commented out (removed assertions, `isRecording = true` left on, tautological checks) without a stated justification silently drops regression protection and should be flagged
- Assertions that have gone stale after a behavior change — asserting a string, parameter, or mock value that no longer matches the implementation — let a test pass without validating the real behavior
- Flaky UI/E2E coverage that leaves the app in a navigation/state the next step does not expect, or relies on fragile or colliding element identifiers, produces false passes and failures unrelated to the behavior under test
- Missing tests for new views, stores, or SQL statements should be reported even when the rest of the diff is otherwise low-risk, since these are the project's designated regression-protection surfaces
