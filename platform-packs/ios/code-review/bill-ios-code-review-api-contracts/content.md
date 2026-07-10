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

Use this specialist for the GraphQL/Apollo-consuming client surface: `.graphql` operation/fragment files, the generated API client code, and Apollo cache configuration. This is a client consuming a GraphQL API, so the contract risk is codegen and cache correctness, not server-side request validation.

## Project-Specific Rules

- Never hand-edit generated API client code (e.g. a generated `API.swift`); any change to server contract behavior must originate from a `.graphql` operation/schema change followed by codegen regeneration
- Every `.graphql` operation or fragment change must be accompanied by regenerated client code in the same diff; a schema/operation change without a matching codegen diff is a contract-drift risk
- Cache and field policies (type policies, merge functions, cache key resolution) must be reviewed whenever a `.graphql` change alters an identifying field, a list field's merge behavior, or a type's cache key
- Nullability changes in `.graphql` operations must be checked against all call sites that unwrap the generated response type; a field going from non-null to nullable (or vice versa) is a breaking client-side change even though the client compiles
- Query/mutation naming and fragment reuse should stay consistent with existing operations touching the same types, to avoid duplicate or conflicting cache entries for the same underlying object
- Optimistic responses and cache writes for mutations must match the shape codegen will regenerate; hand-shaped optimistic payloads that drift from the real response type are a correctness risk
- For Blocker or Major findings, describe the concrete stale-cache, crash-on-unwrap, or data-integrity consequence
