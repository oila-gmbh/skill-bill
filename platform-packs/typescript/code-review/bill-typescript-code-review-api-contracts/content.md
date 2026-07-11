---
name: bill-typescript-code-review-api-contracts
description: Use when reviewing TypeScript exported types, HTTP or RPC contracts, schemas, serialization, events, and compatibility.
internal-for: bill-code-review
---

# API Contracts Review Specialist

## Focus

- Exported functions, classes, interfaces, generics, overloads, and declaration output
- HTTP/RPC inputs, outputs, status mapping, pagination, errors, and idempotency
- JSON, schema, event, message, GraphQL, and generated-client compatibility
- JavaScript consumers and runtime behavior not represented by TypeScript declarations

## Ignore

- Private refactors with no observable contract change
- Naming preferences that do not affect compatibility or clarity
- Requests to expose implementation types solely for caller convenience

## Project-Specific Rules

- Treat exported type changes as caller contracts, including inferred return types and changed generic constraints.
- Do not rely on interfaces or type aliases to validate JSON; parse and validate untrusted values at runtime.
- Preserve absent, optional, nullable, defaulted, zero, and empty semantics across wire formats.
- Keep runtime exports synchronized with declaration exports, package export maps, and documented entry points.
- Validate discriminants and unknown variants so older and newer producers fail or degrade intentionally.
- Preserve stable error classification and machine-readable fields without leaking internal details.
- Account for plain JavaScript callers that can bypass compile-time constraints.
- Findings must identify the broken caller, payload, or version-skew scenario.
