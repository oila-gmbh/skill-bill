---
name: bill-kotlin-code-review-platform-correctness
description: Use when reviewing lifecycle, coroutine, threading, and logic correctness risks in Kotlin code. Use when user mentions coroutine leak, race condition, dispatcher misuse, lifecycle-unsafe collection, or threading bug in Kotlin code.
internal-for: bill-code-review
---

# Platform & Correctness Review Specialist

Review only correctness and runtime-safety issues.

## Focus

- Coroutine ownership, cancellation, supervision, synchronization, Flow delivery, and business invariants

## Ignore

- Style or readability feedback without a reachable correctness failure

## Applicability

Use this specialist for Kotlin concurrency, lifecycle, state, retry, and partial-effect behavior across libraries and services.

## Project-Specific Rules

### Coroutine Cancellation and Ownership

- Never use `GlobalScope`; require every long-lived scope to have an explicit owner and cancellation strategy.
- Rethrow `CancellationException` from `catch (Exception)` and from `runCatching` wrappers around suspend calls so cancellation is never converted into ordinary failure.
- Require `SupervisorJob` when sibling tasks must fail independently; reject a plain `Job` when one child failure would incorrectly cancel unrelated siblings.
- Permit suspending cleanup in `NonCancellable` only when cleanup must complete after cancellation, and require that cleanup to remain bounded.
- Reject retry or lifecycle re-entry that duplicates billing, messages, notifications, or other user-visible effects.

### Synchronization and Flow Semantics

- Reject non-reentrant `Mutex` paths where code holding a mutex calls another path that attempts to acquire the same mutex.
- Verify atomic statements, locking, version checks, or serialization wherever concurrent mutation must preserve an invariant.
- Reject `StateFlow` when equality conflation can suppress a required repeated event or state transition.
- Reject `SharedFlow` with `replay = 0` when late subscribers must receive an event and no durable delivery mechanism exists.

### Truthful Outcomes

- Require result values to report faults truthfully; never return success after a required operation fails.
- Require partial effects to be reported explicitly rather than collapsing partial completion into durable success.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
