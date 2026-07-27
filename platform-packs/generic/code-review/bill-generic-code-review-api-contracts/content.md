---
name: bill-generic-code-review-api-contracts
description: Technology-neutral API contract review for compatibility, validation, representation, and errors.
internal-for: bill-code-review
---

# Generic API Contracts Review

## Focus

Inspect request, response, event, command, and file-format boundaries. Verify required and optional fields, nullability, defaults, versioning, validation, error identity, pagination, idempotency, and backward compatibility for existing consumers.

## Ignore

- Internal representation changes with no consumer-visible contract effect.

## Applicability

Use when changes affect requests, responses, validation, schemas, serialization, or errors.

## Project-Specific Rules

### Contract Rules

- Require each changed `external contract` to preserve documented compatibility and validation; reject silent representation drift that breaks a consumer.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
