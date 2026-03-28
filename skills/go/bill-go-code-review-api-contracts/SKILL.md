---
name: bill-go-code-review-api-contracts
description: Use when reviewing Go backend/service API boundaries including request validation, serialization, HTTP or RPC contracts, status-code mapping, and backward compatibility.
---

# Backend API & Contract Review Specialist

Review only backend/service API contract issues that can break clients, allow invalid behavior, or create hard-to-debug production regressions.

## Focus
- Request validation and boundary enforcement
- Serialization/deserialization mismatches
- Backward compatibility of request/response schemas
- Status-code and error-contract correctness
- Pagination, filtering, streaming, and idempotency semantics on public endpoints

## Ignore
- Pure style feedback
- Internal refactors that do not change externally observable behavior

## Applicability

Use this specialist for Go backend/service code only. It is most relevant for HTTP APIs, gRPC/protobuf services,
webhook handlers, and public or cross-service contracts.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-api-contracts`
section, read that section and apply it as the highest-priority instruction for this skill. The matching section may
refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Validate untrusted input at the boundary before business logic depends on it
- Distinguish absent vs zero vs nil vs null-equivalent fields when that changes semantics; use pointers, wrapper types, or `oneof`/presence-aware fields when optionality is part of the contract
- Do not leak internal/domain/persistence models directly as public API contracts unless that coupling is an explicit, stable decision
- Keep request validation, transport DTO shaping, and domain behavior distinct when the project architecture expects that separation
- Breaking contract changes require explicit versioning, coordinated migration, or a compatibility story
- Error mapping should be stable, intentional, and not collapse distinct client outcomes into the same generic failure
- Ensure validation failures, authentication/authorization failures, and domain/client faults map to stable and distinct API error responses
- Mutating endpoints, commands, and webhook handlers should define idempotency clearly when retries are plausible
- Pagination, filtering, streaming, and list endpoints should preserve deterministic ordering and bounded result sizes
- Serialization defaults, struct tags, `omitempty`, embedded fields, protobuf field presence, and zero-value behavior must match the compatibility expectations of existing clients
- Check enum, date/time, nullability, and default-field serialization for client-visible drift
- Request binders, middleware, and decoder helpers must reject malformed or unknown input when the contract requires strictness instead of silently defaulting fields
- Protobuf/gRPC changes must preserve field numbers, reserved fields, enum compatibility, `oneof` semantics, and streaming behavior expected by existing clients
- Recover middleware, interceptors, and error translators must preserve the intended HTTP/gRPC status and error-contract shape even when handlers panic or return wrapped failures
- Streaming, file, or `io.Reader`/`io.Writer`-style responses must define lifecycle, close, and partial-read/write behavior clearly when clients depend on them
- If the project maintains OpenAPI, protobuf, swagger, or equivalent contract docs, check implementation drift against them

## Output Rules
- Report at most 7 findings.
- Include client-visible consequence for each finding.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
