---
name: bill-rust-code-review-security
description: Use when reviewing Rust unsafe and FFI boundaries, authentication, authorization, parsing, secrets, subprocesses, dependencies, and sensitive data.
internal-for: bill-code-review
---

# Rust Security Review

Review exploitable trust-boundary and supply-chain risks.

## Checks

- Validate authentication and authorization at every reachable entry point and resource boundary.
- Audit `unsafe`, raw pointers, unions, transmute-like operations, C strings, callbacks, and FFI allocation/free ownership.
- Bound parsing, decompression, recursion, allocation, uploads, and protocol lengths before resource use.
- Avoid shell interpretation; constrain subprocess programs, arguments, environment, paths, and inherited handles.
- Keep secrets, tokens, personal data, and credentials out of logs, errors, panic payloads, URLs, and serialized responses.
- Review serde defaults, untagged enums, custom deserializers, path traversal, SSRF, and validation-before-use behavior.
- Check `Cargo.toml`, `Cargo.lock`, build scripts, proc macros, git dependencies, and feature activation against `cargo deny` or `cargo audit` policy.
- Require cryptographic and TLS behavior to use established libraries and repository policy rather than custom primitives.
- Ensure async cancellation and partial failures do not bypass authorization, cleanup, or audit events.

Do not report speculative weakness without an attacker-controlled path and concrete impact.
