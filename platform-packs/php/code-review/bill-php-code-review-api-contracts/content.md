---
name: bill-php-code-review-api-contracts
description: Use when reviewing PHP backend API contracts, request validation, response serialization, status-code mapping, and backward compatibility.
internal-for: bill-code-review
---

# Backend API & Contract Review Specialist

Review only backend/service API contract issues that can break clients, allow invalid behavior, or create hard-to-debug production regressions.

## Focus

- Request validation and boundary enforcement
- Serialization/deserialization mismatches
- Backward compatibility of request/response schemas
- Status-code and error-contract correctness
- Pagination, filtering, and idempotency semantics on public endpoints

## Ignore

- Pure style feedback
- Internal refactors that do not change externally observable behavior

## Applicability

Use this specialist for PHP backend/server code only. It is most relevant for HTTP APIs, RPC endpoints, webhook handlers, and public or cross-service contracts.

## Project-Specific Rules

- Validate untrusted input at the boundary before business logic depends on it
- Distinguish absent vs null vs defaulted fields when that changes semantics
- Do not leak internal/domain/persistence models directly as public API contracts unless that coupling is an explicit, stable decision
- Keep request validation, transport DTO/resource shaping, and domain behavior distinct when the project architecture expects that separation
- Breaking contract changes require explicit versioning, coordinated migration, or a compatibility story
- Error mapping should be stable, intentional, and not collapse distinct client outcomes into the same generic failure
- Ensure validation failures, authentication/authorization failures, and domain/client faults map to stable and distinct API error responses
- Mutating endpoints, commands, and webhook handlers should define idempotency behavior clearly when retries are plausible
- Pagination and filtering should preserve deterministic ordering and bounded result sizes
- Serialization defaults must match the compatibility expectations of existing clients
- Check enum, date/time, nullability, and default-field serialization for client-visible drift
- If the project maintains OpenAPI or equivalent contract docs, check implementation drift against them
- In findings, explain the client-visible consequence of the contract break or boundary bug
