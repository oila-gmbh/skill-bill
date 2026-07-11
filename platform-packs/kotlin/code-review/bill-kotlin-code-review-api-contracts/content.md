---
name: bill-kotlin-code-review-api-contracts
description: Use when reviewing Kotlin backend/server API boundaries including request validation, serialization, HTTP or RPC contracts, status-code mapping, and backward compatibility. Use when user mentions API contract, request validation, response serialization, status codes, or backward compatibility in Kotlin backend.
internal-for: bill-code-review
---

# Backend API & Contract Review Specialist

Review client-visible compatibility and validation failures.

## Focus

- Serialization configuration, absent/default fields, error contracts, validation, and schema evolution

## Ignore

- Internal refactors that cannot change externally observable behavior

## Applicability

Use this specialist for Kotlin HTTP, RPC, DTO, serializer, validation, and public schema boundaries.

## Project-Specific Rules

### Serialization Compatibility

- Require explicit client-impact review for drift in kotlinx.serialization `explicitNulls`, `encodeDefaults`, or `ignoreUnknownKeys`; reject changes that silently alter wire shape or compatibility.
- Verify absent fields are not incorrectly accepted because Kotlin constructor defaults mask required input.
- Reject missing or misconfigured `jackson-module-kotlin` when it lets absent or null input be coerced into non-null constructor parameters or Kotlin defaults and delays failure beyond boundary deserialization.
- Reject enum, date/time, nullability, renamed-field, or default-field drift without versioning, coordinated migration, or a compatibility path.

### Boundary and Error Semantics

- Validate untrusted input before business logic depends on it and preserve the distinction among absent, null, empty, and defaulted values.
- Require distinct stable errors for validation, authentication, authorization, and domain failures; reject generic mappings that erase client-actionable meaning.
- Require deterministic bounded pagination and explicit idempotency semantics for retriable mutations and webhooks.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
