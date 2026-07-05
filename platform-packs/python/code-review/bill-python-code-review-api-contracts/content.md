---
name: bill-python-code-review-api-contracts
description: Review Python API contracts, validation, status codes, schemas, OpenAPI compatibility, and serialization behavior.
internal-for: bill-code-review
---

# Python API Contracts Review

Focus on request, response, and public interface compatibility.

## Review Focus

- Web APIs: FastAPI, Django, Flask, Starlette, DRF, and generic WSGI/ASGI handlers should validate input, return intentional status codes, and preserve documented response shapes.
- Schemas and serialization: pydantic, dataclasses, marshmallow, DRF serializers, OpenAPI generation, enum encoding, datetime/timezone formatting, decimal precision, and nullable fields.
- Error contracts: validation errors, auth errors, not-found behavior, conflict/idempotency responses, and machine-readable error payloads should be stable and consistent.
- Backward compatibility: public function signatures, CLI argument contracts, package entry points, event payloads, webhooks, and versioned endpoints must not break consumers silently.
- Framework edges: dependency injection, middleware, content negotiation, pagination, streaming responses, and background task responses should match framework semantics.

## Findings Standard

Report contract drift that would break clients, tests, generated docs, or integrations. Include the old-vs-new contract when the diff makes it visible.
