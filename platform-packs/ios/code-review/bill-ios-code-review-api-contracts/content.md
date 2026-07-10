---
name: bill-ios-code-review-api-contracts
description: Use when reviewing iOS GraphQL/Apollo API-contract risks including generated code drift, cache/field-policy correctness, and schema/codegen alignment.
internal-for: bill-code-review
---

# API Contracts Review Specialist

Review only high-signal API-contract issues.

## Focus

- Generated GraphQL client code staying in sync with schema/operation changes
- Apollo cache and field-policy correctness
- `.graphql` operation changes that alter response shape or nullability
- Codegen regeneration discipline

## Ignore

- Non-networking code with no GraphQL/Apollo surface
- Cosmetic differences in generated code that codegen would reproduce identically

## Applicability

Use the REST/`Codable` branch for URLSession or other HTTP clients, request/response DTOs, and structured error payloads. Use the Apollo/GraphQL branch for `.graphql` operations and fragments, generated API client code, schema changes, and Apollo cache configuration.

## Project-Specific Rules

### REST And Codable Contracts

- `JSONDecoder` and `JSONEncoder` date, key, and data strategies must remain compatible with the server contract; reject a strategy change that silently changes wire keys or values
- Required or non-optional `Codable` fields must have an explicit failure or compatibility plan when older, partial, or malformed payloads can omit them; never hide a contract failure behind an unrelated default
- HTTP clients must map every expected success and failure status deliberately rather than decoding an error body as a success model or treating every non-2xx response identically
- Structured server error payloads must be decoded and preserved when mapping transport failures into app errors so actionable status, code, and field-level details are not discarded

### Apollo And GraphQL Contracts

- Never hand-edit generated API client code (e.g. a generated `API.swift`); any change to server contract behavior must originate from a `.graphql` operation/schema change followed by codegen regeneration
- Every `.graphql` operation or fragment change must be accompanied by regenerated client code in the same diff; a schema/operation change without a matching codegen diff is a contract-drift risk
- Cache and field policies (type policies, merge functions, cache key resolution) must be reviewed whenever a `.graphql` change alters an identifying field, a list field's merge behavior, or a type's cache key
- Nullability changes in `.graphql` operations must be checked against all call sites that unwrap the generated response type; a field going from non-null to nullable (or vice versa) is a breaking client-side change even though the client compiles
- Query/mutation naming and fragment reuse should stay consistent with existing operations touching the same types, to avoid duplicate or conflicting cache entries for the same underlying object
- Optimistic responses and cache writes for mutations must match the shape codegen will regenerate; hand-shaped optimistic payloads that drift from the real response type are a correctness risk
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
