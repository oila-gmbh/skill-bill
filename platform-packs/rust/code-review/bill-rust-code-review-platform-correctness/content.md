---
name: bill-rust-code-review-platform-correctness
description: Use when reviewing Rust ownership, borrowing, lifetime, unsafe, trait, error, panic, concurrency, pinning, and async correctness.
internal-for: bill-code-review
---

# Rust Platform-Correctness Review

Report reachable behavior or safety defects, not stylistic preferences.

## Checks

- Verify moves, borrows, interior mutability, aliasing, and shared state preserve the intended invariant across all paths.
- Every `unsafe` block must have a valid, maintained invariant covering pointers, initialization, alignment, provenance, aliasing, lifetime, and drop behavior.
- Review `Send` and `Sync` implementations, pin projections, self-references, and FFI ownership for soundness.
- Preserve `Result` error distinctions and causes; reject silent fallback or discarded errors that change the contract.
- Treat `panic!`, `unwrap`, and `expect` as defects on reachable untrusted or operational paths unless the invariant is explicit and proven.
- Ensure trait bounds, object safety, generics, and lifetime relationships express the public contract without accepting invalid states.
- Verify Tokio or async-std futures are awaited, cancelled, detached, or joined intentionally and do not hold unsuitable guards across `.await`.
- Check state transitions, retries, duplicate delivery, time handling, integer conversion, indexing, and partial-success paths.
- Confirm Cargo feature combinations do not remove required implementations or expose incompatible behavior.

For serious findings, name the triggering input or interleaving and the violated invariant.
