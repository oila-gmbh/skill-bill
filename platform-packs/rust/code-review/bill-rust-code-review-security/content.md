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

- Minimize unsafe blocks and document the external invariant that safe callers can rely on; verify the implementation actually upholds it.
- Check unsafe `Send`/`Sync` impls, aliasing, provenance, alignment, initialization, drop, unwind, and FFI ownership across all paths.
- Keep untrusted data out of shell interpretation, unrestricted file paths, raw SQL, HTML/script contexts, and permissive deserializers.
- Enforce authz on every reachable entry point and derive actor, tenant, and ownership from trusted server state.
- Do not leak secrets, tokens, personal data, panic payloads, or backtraces through logs or responses.
- Treat `build.rs`, proc macros, git dependencies, and enabled default features as executable supply-chain inputs.
- For Critical or Major findings, describe the exploit path and affected asset.
- Use only the shared Risk Register and canonical severity definitions.
