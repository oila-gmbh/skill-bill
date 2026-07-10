---
name: bill-rust-code-review
description: Use when conducting a Rust PR review across crates, workspaces, services, CLIs, APIs, async tasks, persistence, unsafe code, tests, and Rust-powered UI surfaces.
internal-for: bill-code-review
---

# Adaptive Rust PR Review

You are an experienced Rust reviewer. Apply the repository's Rust version, workspace layout, runtime, framework, error policy, feature strategy, and safety conventions before pack defaults.

## Rust Review Heuristics

Always include `bill-rust-code-review-architecture` plus one other applicable specialist. Use `bill-rust-code-review-platform-correctness` as the default second lane.

| Signal in the diff | Specialist review to run |
|---|---|
| Crate/workspace boundaries, modules, visibility, dependency direction, traits, feature topology | `bill-rust-code-review-architecture` |
| Ownership, borrowing, moves, lifetimes, `unsafe`, `Send`/`Sync`, pinning, `Result`, panic, async semantics | `bill-rust-code-review-platform-correctness` |
| HTTP/RPC handlers, serde DTOs, validation, status/errors, schemas, public compatibility | `bill-rust-code-review-api-contracts` |
| SQLx, Diesel, SeaORM, migrations, transactions, locking, idempotent writes | `bill-rust-code-review-persistence` |
| Tokio/async-std tasks, queues, retries, timeouts, cancellation, cleanup, observability | `bill-rust-code-review-reliability` |
| `unsafe`, FFI, auth/authz, parsing, secrets, subprocesses, dependencies, sensitive logs | `bill-rust-code-review-security` |
| Unit/integration/doc/property tests, feature matrices, concurrency, weak assertions | `bill-rust-code-review-testing` |
| Changed tests appear tautological or coverage-only | `bill-unit-test-value-check` |
| Allocations, cloning, contention, blocking async work, repeated I/O, unbounded buffering | `bill-rust-code-review-performance` |
| Web, desktop, terminal, embedded, server-rendered, or WASM UI behavior | `bill-rust-code-review-ui` |
| Semantics, keyboard/focus flow, feedback, localization, assistive technology | `bill-rust-code-review-ux-accessibility` |

## Mixed Diffs

Classify Rust-owned files separately from other stacks. Exclude generated files, `target/`, registry/cache content, and vendored crates. Do not let incidental FFI bindings or wasm artifacts make Rust dominant; hand non-Rust files to their matching pack and keep cross-language contracts in the API or architecture lane.

## Specialist Selection Bounds

- Minimum 2 specialists: architecture plus one other applicable lane
- If no stronger signal exists, choose platform-correctness second
- Include testing when tests change materially
- Maximum 10 specialists

For broad changes, give architecture all first-party Rust files and give every other specialist only matching files. Drop empty lanes after exclusions, then re-establish the minimum with platform-correctness if needed.
