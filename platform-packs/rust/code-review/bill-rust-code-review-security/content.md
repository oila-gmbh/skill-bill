---
name: bill-rust-code-review-security
description: Use when reviewing Rust unsafe code, FFI, auth, secrets, untrusted parsing, process and file boundaries, dependencies, and sensitive data.
internal-for: bill-code-review
---

# Security Review Specialist

## Focus

- `unsafe`, raw pointers, FFI, transmute, uninitialized memory, and unsafe trait impls
- Authentication, authorization, tenant isolation, secrets, sensitive logs, and cryptography
- Untrusted parsing, deserialization, paths, URLs, subprocesses, templates, and network boundaries
- Cargo dependencies, build scripts, proc macros, advisories, licenses, and feature-enabled attack surface

## Ignore

- Safe-code style comments without an exploit or policy consequence
- Generic dependency concern without affected version, feature, provenance, or reachable surface
- Requests to replace audited unsafe code when its invariants remain established

## Applicability

Apply at every trust boundary and whenever the diff adds or changes unsafe behavior, dependency trust, privileges, identity, or sensitive data flow.

## Project-Specific Rules

### Rust Security Rules

- Require every safe wrapper around `unsafe` or `NonNull<T>` to validate provenance, alignment, initialization, aliasing, and lifetime invariants; reject a caller-controlled path to undefined behavior.
- Verify `extern "C"` functions prevent unwinding with `catch_unwind` or an abort contract and define allocation ownership; reject cross-ABI unwind or double-free exposure.
- Ensure `unsafe impl Send` and `unsafe impl Sync` account for raw pointers, callbacks, and thread affinity; flag data races or use-after-free reachable from safe callers.
- Require untrusted parsers using `serde_json::from_slice`, `nom`, or archive decoders to enforce depth, size, and allocation limits; reject denial-of-service or invalid-data acceptance.
- Construct subprocesses with `std::process::Command::arg` and never interpolate untrusted text into `sh -c`; reject command injection or argument confusion.
- Require file access to canonicalize both the trusted root and candidate with `Path::canonicalize` before containment checks; reject traversal, symlink escape, or time-of-check races.
- Ensure SQL values use driver parameters such as `sqlx::query(...).bind(...)`; reject string-built predicates that enable injection or authorization bypass.
- Derive actor, tenant, and ownership from trusted middleware extensions rather than request fields; reject any entry point that can expose another tenant's data.
- Keep `SecretString`, tokens, personal data, panic payloads, and backtraces out of `tracing` fields and responses; reject sensitive-data exposure through diagnostics.
- Verify cryptographic comparison and token generation use maintained primitives such as `subtle::ConstantTimeEq` and `rand::rngs::OsRng`; reject timing exposure or predictable secrets.
- Treat `build.rs`, proc macros, and git dependencies in `Cargo.lock` as executable supply-chain inputs; reject unpinned provenance, unexpected default features, or unaudited build access.
- Require unsafe FFI buffers to pair lengths with pointers and validate nullability before `slice::from_raw_parts`; reject integer overflow, out-of-bounds reads, or memory corruption.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
