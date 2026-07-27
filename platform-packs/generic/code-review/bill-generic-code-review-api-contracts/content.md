---
name: bill-generic-code-review-api-contracts
description: Technology-neutral API contract review for compatibility, validation, representation, and errors.
internal-for: bill-code-review
---

# Generic API Contracts Review

## Review Focus

Inspect request, response, event, command, and file-format boundaries. Verify required and optional fields, nullability, defaults, versioning, validation, error identity, pagination, idempotency, and backward compatibility for existing consumers.

## Evidence

Identify the consumer expectation and the exact changed representation or behavior that breaks it. Treat internal refactors as non-findings when the external contract is preserved.
