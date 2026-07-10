---
name: bill-rust-code-review-api-contracts
description: Use when reviewing Rust HTTP, RPC, serialization, validation, error, schema, trait, and public API compatibility contracts.
internal-for: bill-code-review
---

# Rust API-Contracts Review

Review externally observable contract drift.

## Checks

- Preserve routes, methods, status codes, headers, request validation, response shapes, pagination, and idempotency behavior.
- Review serde rename/default/skip/flatten/tag behavior and custom serializers for backward-compatible wire formats.
- Map domain, validation, authentication, operational, and unavailable failures to stable public errors without leaking internals.
- Keep OpenAPI, protobuf, GraphQL, or other schemas synchronized through repository-owned generation paths.
- Treat public traits, structs, enums, generics, lifetimes, feature gates, and re-exports as compatibility surfaces for library crates.
- Flag new required fields, exhaustive enum growth, changed trait bounds, removed implementations, and feature-dependent API disappearance.
- Validate size limits and content types before expensive parsing or allocation.
- Keep FFI ABI, ownership, nullability, panic containment, and versioning explicit when the public boundary is C or another language.
- Require contract tests for high-risk compatibility or error-mapping changes.

Ignore internal refactors that leave observable behavior unchanged.
