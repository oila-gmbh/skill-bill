---
name: bill-rust-code-review-architecture
description: Use when reviewing Rust crate and workspace boundaries, dependency direction, module visibility, trait ownership, and Cargo feature composition.
internal-for: bill-code-review
---

# Rust Architecture Review

Review only concrete boundary, ownership, or maintainability risks.

## Checks

- Preserve coherent crate and workspace responsibilities; avoid cyclic or convenience dependencies that blur ownership.
- Keep `pub`, `pub(crate)`, sealed traits, re-exports, and module layout as narrow as the intended API requires.
- Place traits with the consumer or stable abstraction owner and avoid trait surfaces that leak transport or persistence details.
- Review generic parameters and lifetimes for API necessity; flag designs that make callers carry implementation lifetimes or types.
- Keep domain behavior independent of framework, executor, serialization, and database details when local architecture requires it.
- Treat Cargo features as additive capabilities: avoid mutually inconsistent combinations, accidental default expansion, and feature-unification surprises.
- Keep build scripts, proc macros, FFI crates, and generated bindings behind explicit boundaries with clear ownership.
- Preserve a single transaction and orchestration owner for multi-step behavior.

Ignore naming or pattern preferences without a concrete dependency, compatibility, testability, or ownership consequence.
