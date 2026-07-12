---
name: bill-kotlin-code-review-platform-correctness
description: Review Kotlin type, interop, coroutine, synchronization, and Flow correctness. Use for Kotlin runtime and language-semantics failures.
internal-for: bill-code-review
---

# Platform and Correctness Review Specialist

## Focus

- Kotlin types, Java interop, cancellation, supervision, dispatchers, synchronization, and Flow delivery

## Ignore

- Readability feedback without a concrete incorrect state or runtime failure

## Applicability

Use across Kotlin/JVM libraries and services. Android and Compose Multiplatform target behavior belongs to KMP.

## Project-Specific Rules

### Correctness Review Rules

- Require platform values from Java such as `String!` to be validated before dereference; unchecked nullability can crash at `value.length`.
- Reject unsafe generic casts around `List<*>` or erased Java collections because incorrect variance can corrupt data or throw `ClassCastException`.
- Verify `data class` equality excludes mutable identity-sensitive state; unstable `equals` can break map membership and Flow state delivery.
- Require exhaustive handling for `sealed interface` variants; an `else` branch can hide a new contract state and cause incorrect behavior.
- Reject Java-facing defaults that rely only on Kotlin optional parameters; missing `@JvmOverloads` or a bridge can break JVM caller compatibility.
- Require `CancellationException` to be rethrown from `catch (Exception)` and `runCatching`; swallowing it leaks lifecycle work and breaks cancellation ordering.
- Verify `SupervisorJob` is used only where sibling failure isolation is intended; wrong supervision can either cancel healthy work or hide fatal failure.
- Reject blocking calls on `Dispatchers.Default` and UI dispatchers; `Thread.sleep` or JDBC there risks thread starvation and timeout.
- Require `Mutex` ownership paths to avoid reentrant acquisition; a holder calling another `withLock` path can deadlock concurrent state changes.
- Verify shared mutation uses `AtomicReference`, `Mutex`, or confinement; a plain `var` updated by multiple coroutines risks races and lost data.
- Reject `StateFlow` for distinct repeated events because equality conflation can lose required delivery; use an event contract with observable ordering.
- Require bounded `SharedFlow` or channel overflow policy to match producer guarantees; unbounded buffering risks memory failure while dropped values break contracts.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
