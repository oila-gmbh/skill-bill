# SKILL-114 Subtask 5 - Rust Pack Depth

## Scope

Replace Rust's scaffold-heavy first version with an expert Rust pack across all
ten areas. Rules must name Rust mechanisms and distinguish compile-time
guarantees from runtime, unsafe, FFI, async, persistence, UI, and operational
failure modes.

Required depth includes ownership/borrowing/lifetimes and interior mutability,
Send/Sync/pinning and async cancellation, unsafe invariants and FFI ABI/lifetime
boundaries, error and panic policy, workspace/crate/features/dependency
architecture, allocation/locking/executor/I/O behavior, serde and public trait
or wire compatibility, SQLx/Diesel/SeaORM or raw-client transaction/migration
risks when detected, supply chain/secrets/parsing/path/process security,
unit/integration/doc/property/fuzz/miri/loom evidence as applicable,
shutdown/backpressure/retry/observability, and wasm/server-rendered/desktop/TUI
UI plus accessibility.

Quality checking must discover workspace commands and cover formatting,
clippy/check/build/test/doc tests, feature/target matrices, lock/dependency
policy, audit/deny, generated bindings, unsafe-focused tools where configured,
and targeted-to-full escalation.

## Acceptance Criteria

1. All ten Rust specialists meet the substance gate and no rule uses a generic
   phrase such as `` `Rust ... APIs` `` in place of a real mechanism.
2. Correctness, architecture, performance, reliability, and security cover
   ownership/lifetime/interior-mutability, Send/Sync/pinning, unsafe/FFI,
   crate/features, allocation/locking/executors, cancellation/backpressure,
   parsing/process/path/dependency and shutdown invariants.
3. API, persistence, and testing cover trait/wire/serde/FFI compatibility,
   transaction/migration/client semantics, and appropriate unit/integration/
   doc/property/fuzz/miri/loom evidence without requiring every tool in every
   repository.
4. UI and UX/accessibility contain applicability-gated depth for wasm,
   server-rendered, native GUI and terminal interfaces, including state,
   events, keyboard/focus, semantics, feedback, localization and assistive
   access.
5. The quality checker covers discovered commands plus Cargo workspace,
   formatting, clippy/check/build/test/doc, feature/target, dependency/security,
   bindings and configured unsafe-analysis paths.
6. Rust passes both duplication thresholds, including every Rust/TypeScript
   corresponding rubric.
7. Manifest metadata, agents, tests, and history reflect the completed Rust
   expertise.
8. Rust pack tests, `skill-bill validate`, and relevant Gradle checks pass.

## Non-Goals

- No requirement to run every specialist Rust tool when the repo does not use
  or configure it.
- No one GUI or async runtime is treated as universal.
- No TypeScript edits.

## Dependency Notes

Depends on subtask 1. Independent of other pack elevations.

## Validation Strategy

Run the maintained-pack audit for Rust, focused Rust pack tests, `skill-bill
validate`, and the relevant Gradle suite.

## Next Path

Proceed independently; subtask 10 waits for completion.
