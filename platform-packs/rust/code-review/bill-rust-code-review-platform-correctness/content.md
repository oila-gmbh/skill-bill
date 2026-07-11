---
name: bill-rust-code-review-platform-correctness
description: Use when reviewing Rust ownership, borrowing, lifetimes, error semantics, panic behavior, concurrency, and async runtime correctness.
internal-for: bill-code-review
---

# Platform Correctness Review Specialist

## Focus

- Ownership, borrowing, lifetimes, interior mutability, pinning, and drop order
- `Result` propagation, error conversion, panic boundaries, `unwrap`/`expect`, and invariant enforcement
- `Send`/`Sync`, locks, atomics, channels, races, task lifetime, and cancellation
- tokio/async-std runtime semantics, blocking boundaries, time, and feature-gated behavior

## Ignore

- Compiler errors the changed code cannot build with
- `unwrap`, `expect`, `panic!`, or cloning solely by keyword without a reachable failure
- Idiom preferences that do not change correctness, diagnostics, or maintainability

## Applicability

Apply when changed Rust code affects ownership, borrowing, error behavior, unsafe invariants, concurrency, feature combinations, or runtime semantics.

## Project-Specific Rules

### Rust Platform Correctness Rules

- Verify `Rust lifecycle and concurrency APIs` preserve their documented invariants; reject an invalid state or ordering failure.
- Verify borrowed data outlives every task, callback, FFI call, and stored reference under the actual ownership flow.
- Treat `unsafe` contracts as caller/callee obligations: every dereference, aliasing, alignment, initialization, and lifetime invariant must be established.
- Preserve error identity and context across `?`, `map_err`, anyhow/eyre reports, and typed domain errors; do not turn recoverable failures into panics.
- Permit `unwrap`/`expect` only where the invariant is local, proven, and stable; user input, I/O, configuration, concurrency, and remote data are not proofs.
- Check async cancellation points for partially applied state, leaked permits, orphan tasks, and guards held across `.await`.
- Confirm runtime handles, timers, channels, and spawn APIs match the selected tokio/async-std configuration and supported targets.
- Evaluate every supported Cargo feature combination affected by conditional types, impls, or dependencies.
- Findings must give a reproducible state transition or invariant failure and use only canonical severities.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
