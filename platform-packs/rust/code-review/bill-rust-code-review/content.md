---
name: bill-rust-code-review
description: Use when conducting a Rust PR review across crates, workspaces, libraries, services, CLIs, async code, FFI, persistence, tests, and Rust-owned UI surfaces.
internal-for: bill-code-review
---

# Adaptive Rust PR Review

Review Rust changes against the repository's edition, MSRV, Cargo workspace, feature policy, async runtime, error conventions, safety documentation, and public API compatibility. Treat ownership and type-system success as a starting point, not proof that runtime behavior is correct.

## Classification Rules

- Always select `bill-rust-code-review-architecture`.
- Select at least two and at most ten specialists.
- Use `bill-rust-code-review-platform-correctness` as the default second lane when no narrower signal applies.
- Select `bill-rust-code-review-testing` for material changes to tests, fixtures, feature matrices, concurrency, or regressions.
- Review `unsafe`, FFI, raw pointers, auth, parsing, and dependency trust through the security lane as well as the owning behavior lane.
- Check ownership and borrowing choices for semantic bugs, accidental cloning, invalid lifetime assumptions, lock scope, and API constraints rather than asking whether the code compiles.

## Diff-Signal Routing Table

| Signal in the diff | Specialist review to run |
|---|---|
| Crate/workspace layout, dependency direction, trait ownership, public abstractions, Cargo feature topology | `bill-rust-code-review-architecture` |
| Borrowing, lifetimes, pinning, `Send`/`Sync`, interior mutability, `Result`, `panic!`, `unwrap`/`expect`, tokio/async-std semantics | `bill-rust-code-review-platform-correctness` |
| Traits, public types, serde shapes, HTTP/RPC, FFI ABI, semver compatibility | `bill-rust-code-review-api-contracts` |
| SQLx, Diesel, SeaORM, migrations, transactions, locking, durable writes | `bill-rust-code-review-persistence` |
| Cancellation, backpressure, retries, channels, tasks, timeouts, shutdown, observability | `bill-rust-code-review-reliability` |
| `unsafe`, FFI, raw memory, auth, secrets, untrusted parsing, process/file/network boundaries, Cargo supply chain | `bill-rust-code-review-security` |
| Unit, integration, doc, property, fuzz, compile-fail, concurrency, and feature-combination tests | `bill-rust-code-review-testing` |
| Allocation, cloning, boxing, locking, atomics, serialization, async scheduling, repeated I/O | `bill-rust-code-review-performance` |
| wasm, server-rendered HTML, desktop GUI, terminal UI, interactive CLI output | `bill-rust-code-review-ui` |
| Semantics, keyboard/focus flow, accessible names, feedback, localization | `bill-rust-code-review-ux-accessibility` |

## Mixed Diffs

Classify each changed file independently. Architecture receives every first-party Rust-owned file; other specialists receive only matching files. Exclude generated sources, vendored crates, `target/`, build artifacts, and non-Rust-owned files. Rust used only for FFI bindings or wasm build support does not make the whole repository Rust-owned. After exclusions, drop empty lanes and restore the minimum by assigning all Rust-owned files to platform-correctness when architecture would otherwise stand alone.

## Finding Discipline

Report only evidence-backed defects or material risks introduced by the diff. Name the violated Rust, repository, API, or operational contract; explain the concrete failure mode; and propose the smallest compatible correction. Do not report formatting handled by rustfmt, subjective style, speculative micro-optimizations, or `unwrap`/`unsafe` merely by keyword: show the reachable failure or unproven invariant. Use the shared F-XXX Risk Register and canonical severities.
