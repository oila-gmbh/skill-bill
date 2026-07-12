---
name: bill-kotlin-code-review-api-contracts
description: Review Kotlin serialization, null/default, enum, time, validation, error, pagination, idempotency, and compatibility contracts.
internal-for: bill-code-review
---

# API Contracts Review Specialist

## Focus

- Wire shape, serializer configuration, validation, errors, compatibility, pagination, and idempotency

## Ignore

- Internal refactors that do not change an observable service or library contract

## Applicability

Use for Kotlin DTOs, HTTP endpoints, events, public services, and generated schemas.

## Project-Specific Rules

### API Contract Review Rules

- Require `Json { explicitNulls = ... }` to match the published absent-versus-null contract; configuration drift can silently corrupt client intent.
- Verify `encodeDefaults` behavior before adding a DTO default; changed omission can break signatures, caches, or compatibility.
- Reject renaming a `@SerialName` value without migration evidence; deployed payloads can fail deserialization and data replay.
- Require Jackson endpoints to register `jackson-module-kotlin`; missing Kotlin constructor and null handling can cause invalid runtime binding.
- Verify unknown enum behavior with an explicit fallback or rejection contract; a new wire value can crash older consumers.
- Require `Instant` fields to declare wire format, offset normalization, and timezone conversion policy; inconsistent conversions risk incorrect ordering and persisted data. Require `LocalDate` fields to declare calendar and format semantics while remaining zone-free, and reject implicit timezone conversion of date-only values because it can shift the represented date.
- Reject exposing inline or value classes without verifying `@JvmInline` serializer and OpenAPI shape; generated clients can receive an incompatible contract.
- Require Bean Validation such as `@field:NotBlank` on the effective Kotlin target; misplaced annotations can skip validation and admit invalid data.
- Verify Ktor or Spring exception mapping preserves stable status and error codes; leaking `exception.message` risks exposure and contract regression.
- Require pagination to define stable sort keys, cursor encoding, and bounds; offset-only mutation can duplicate or lose records between pages.
- Reject retriable mutation endpoints without an `Idempotency-Key` contract; network timeout recovery can duplicate state changes.
- Require executable compatibility evidence from `apiCheck`, generated OpenAPI diff, or consumer tests; declaration review alone can miss binary or wire breakage.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
