---
name: bill-python-code-review-api-contracts
description: Review Python API contracts, validation, status codes, schemas, OpenAPI compatibility, and serialization behavior.
internal-for: bill-code-review
---

# Python API Contracts Review

Review request, response, and public interface compatibility.

## Focus

- Validation, status codes, schemas, serialization, error payloads, pagination, idempotency, and backward compatibility

## Ignore

- Internal refactors that cannot change externally observable behavior
- Framework preferences without a concrete client, documentation, or integration consequence

## Applicability

Use this specialist for FastAPI, Django, Flask, Starlette, DRF, WSGI/ASGI handlers, public Python APIs, CLI contracts, event payloads, webhooks, and versioned endpoints.

## Project-Specific Rules

### Input and Serialization Semantics

- Require input validation before business logic and intentional status codes with stable documented response shapes.
- Preserve the distinction among absent, null, empty, and defaulted values; verify pydantic `exclude_unset` and PATCH handlers do not overwrite omitted fields or collapse them into explicit nulls.
- Reject pydantic v1-to-v2 migration drift in dump methods, aliases, defaults, enum encoding, datetime/timezone formatting, decimal precision, nullable fields, or validation behavior when it changes the wire contract.
- Verify dataclasses, marshmallow, DRF serializers, OpenAPI generation, dependency injection, middleware, content negotiation, streaming responses, and background-task responses match their framework boundary semantics.

### Compatibility and Errors

- Preserve stable machine-readable validation, authentication, authorization, not-found, conflict, and idempotency error shapes; reject mappings that erase client-actionable distinctions.
- Require explicit idempotency semantics for retriable mutations and webhooks, including deterministic replay responses.
- Require deterministic, bounded pagination with a stable ordering and cursor or tie-breaker so concurrent writes cannot duplicate or omit records unpredictably.
- Reject silent breaks to public signatures, package entry points, CLI arguments, schemas, event payloads, generated documentation, or integrations; include the old-versus-new contract when the diff exposes it.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
