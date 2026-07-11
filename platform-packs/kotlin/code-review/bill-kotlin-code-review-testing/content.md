---
name: bill-kotlin-code-review-testing
description: Use when reviewing test coverage quality, real test value, regression protection, and test reliability risks in Kotlin code. Use when user mentions test quality, test coverage, mock setup, or test reliability in Kotlin code.
internal-for: bill-code-review
---

# Testing Review Specialist

Review test gaps and false confidence that create real regression risk.

## Focus

- Coroutine virtual time, ordering, Flow sequences, persistence effects, negative paths, and behavior-focused assertions

## Ignore

- Test style preferences and trivial accessors or generated mappings without behavior

## Applicability

Use this specialist for Kotlin unit, integration, contract, persistence, and coroutine tests.

## Project-Specific Rules

### Coroutine and Flow Tests

- Require `runTest` and virtual-time controls such as `advanceTimeBy` and `advanceUntilIdle` when retries, delays, or timeouts determine behavior.
- Verify whether `StandardTestDispatcher` queueing or `UnconfinedTestDispatcher` eager execution matches the production ordering being asserted; reject tests that pass only because the dispatcher hides a race.
- Require controlled Flow collection, such as an explicit collector or Turbine, when emission order, repetition, cancellation, or completion matters; reject a single `first()` snapshot as sequence proof.

### Behavioral Evidence

- Verify real persistence effects with an integration boundary when transaction, query, migration, constraint, or ORM behavior changed; mock interaction alone is not proof.
- Assert each failure type separately when validation, authentication, authorization, domain, timeout, transient, permanent, and duplicate-delivery negative paths have different contracts.
- Reject tautological tests, tests that merely return a stubbed value, and implementation-mirroring assertions that cannot detect a meaningful behavior regression.
- Require boundary-level coverage when serialization, persistence, authorization, or transport contracts change.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
