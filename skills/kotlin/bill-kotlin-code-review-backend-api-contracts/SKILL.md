---
name: bill-kotlin-code-review-backend-api-contracts
description: Use when reviewing Kotlin backend/server API boundaries including request validation, serialization, HTTP or RPC contracts, status-code mapping, and backward compatibility.
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

Use this specialist for backend/server code only. It is most relevant for Ktor, Spring, Micronaut, Quarkus, http4k, Javalin, gRPC, or similar transport layers.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-backend-api-contracts` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Validate untrusted input at the boundary before business logic depends on it
- Distinguish absent vs null vs defaulted fields when that changes semantics
- Do not leak internal/domain/persistence models directly as public API contracts unless that coupling is an explicit, stable decision
- Breaking contract changes require explicit versioning, coordinated migration, or a compatibility story
- Error mapping should be stable, intentional, and not collapse distinct client outcomes into the same generic failure
- Mutating endpoints, commands, and webhook handlers should define idempotency behavior clearly when retries are plausible
- Pagination and filtering should preserve deterministic ordering and bounded result sizes
- Serialization defaults must match the compatibility expectations of existing clients

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
