---
name: bill-rust-code-review-api-contracts
description: Use when reviewing Rust public traits and types, serde and wire formats, HTTP or RPC contracts, FFI ABI, and compatibility.
internal-for: bill-code-review
---

# API Contracts Review Specialist

## Focus

- Public functions, traits, associated types, generics, lifetimes, errors, and semver surface
- Serde names, defaults, optionality, enum representations, unknown fields, and wire compatibility
- HTTP/RPC validation, status mapping, pagination, idempotency, and error payloads
- `extern` ABI, layout, ownership, unwinding, and cross-language compatibility

## Ignore

- Private refactors with no observable contract change
- Naming preference that does not reduce clarity or compatibility
- Demands to expose implementation types for caller convenience

## Applicability

Apply to library APIs, service boundaries, serialized data, messages, plugins, and FFI. Compare the diff with documented compatibility and MSRV policy.

## Project-Specific Rules

### Rust API Contract Rules

- Verify `Rust request and serialization APIs` preserve their documented invariants; reject a compatibility or validation failure.
- Treat new public trait requirements, generic bounds, lifetime constraints, and non-exhaustive enum changes as caller-impacting contracts.
- Preserve error classification and stable machine-readable fields while avoiding accidental exposure of internal details.
- Ensure serde defaults and optional fields distinguish absent, null, zero, and empty values as the external protocol intends.
- Keep feature-gated public APIs available in documented combinations and prevent default-feature leakage into supposedly optional contracts.
- At FFI boundaries, pin ABI, layout, allocation owner, string encoding, nullability, thread rules, and panic/unwind behavior.
- Require request validation before domain work and map every failure to the intended status or protocol code.
- Findings must identify the broken caller or payload scenario and use only canonical severities.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
