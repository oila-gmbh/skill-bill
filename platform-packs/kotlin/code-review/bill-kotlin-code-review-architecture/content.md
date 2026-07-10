---
name: bill-kotlin-code-review-architecture
description: Use when reviewing architecture, boundaries, DI scopes, and source-of-truth consistency in Kotlin code. Use when user mentions Kotlin architecture, DI scope, module boundaries, or dependency direction in Kotlin code.
internal-for: bill-code-review
---

# Architecture Review Specialist

Review only concrete ownership, dependency, lifecycle, or module-boundary failures.

## Focus

- Use-case ownership, transaction ownership, event durability, coroutine scope ownership, visibility, and Gradle modules

## Ignore

- Naming, layering, or framework preferences that conflict with established local architecture but do not create a concrete risk

## Applicability

Use this specialist across Kotlin libraries and services. Treat documented repository architecture as authoritative before generic patterns and report only reachable boundary failures.

## Project-Specific Rules

### Ownership and Durability

- Require one use-case owner for each business workflow; reject duplicated orchestration across transports, jobs, and adapters when behavior can drift.
- Require one transaction owner for each atomic business operation; reject nested ownership that silently splits or widens the boundary.
- Persist domain events in the same transaction as business state, typically through an outbox, when losing or duplicating the event would violate an invariant.
- Preserve one source of truth and explicit translation among transport, domain, and persistence models when their ownership or lifecycle differs.

### Kotlin Boundaries

- Require blocking or asynchronous contracts to use `suspend` only when suspension is part of the boundary; reject decorative suspend APIs that hide blocking work.
- Require long-lived background work to receive an injected `CoroutineScope` with an explicit lifecycle instead of constructing hidden global scopes.
- Keep implementation details `internal` when cross-module access is not part of the supported API.
- Enforce Gradle module dependency direction and reject cycles or implementation dependencies that leak across owned boundaries.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
