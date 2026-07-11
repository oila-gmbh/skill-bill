---
name: bill-rust-code-review-architecture
description: Use when reviewing Rust crate boundaries, dependency direction, trait ownership, public abstractions, and Cargo feature architecture.
internal-for: bill-code-review
---

# Architecture Review Specialist

## Focus

- Crate and workspace boundaries, dependency direction, and source-of-truth ownership
- Trait placement, object-safety choices, generic abstraction cost, and public type ownership
- Cargo feature topology, optional dependencies, default features, and supported combinations
- Application, domain, transport, persistence, FFI, and runtime lifecycle boundaries

## Ignore

- Module layout or naming preferences without a concrete ownership or dependency problem
- Demands for traits, generics, or extra crates where a local concrete implementation is clearer
- Formatting and compiler-enforced concerns with no architectural consequence

## Applicability

Apply to every first-party Rust diff. Infer the existing workspace architecture and MSRV before judging it. Review cross-crate changes as one dependency graph, including build scripts and feature-gated edges.

## Project-Specific Rules

- Keep stable business rules independent of web, async-runtime, database, UI, and serialization details when the repository establishes that boundary.
- Put traits where their consumers own the required contract; avoid broad provider-owned traits that leak implementation details or make downstream evolution semver-sensitive.
- Do not use lifetimes as hidden global coupling. Public borrowed types must have a clear owner and usable lifetime contract.
- Keep feature flags additive and coherent: no accidental mutually exclusive defaults, hidden dependency activation, or combinations that cannot compile.
- Avoid cyclic conceptual ownership disguised by callback traits, global registries, service locators, or shared mutable singletons.
- Treat proc macros and build scripts as architectural dependencies when they generate APIs or influence compilation.
- Findings must identify the misplaced responsibility, violated boundary, and concrete maintenance, compatibility, or correctness risk.
- Use only the shared Risk Register and canonical severity definitions.
