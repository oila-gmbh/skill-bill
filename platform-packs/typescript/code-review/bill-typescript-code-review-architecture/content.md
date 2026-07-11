---
name: bill-typescript-code-review-architecture
description: Use when reviewing TypeScript package and module boundaries, dependency direction, public abstractions, project references, and runtime ownership.
internal-for: bill-code-review
---

# Architecture Review Specialist

## Focus

- Package, workspace, project-reference, and module boundaries
- Dependency direction and ownership across domain, transport, persistence, UI, and infrastructure
- Public types, abstractions, dependency injection, and lifecycle ownership
- ESM/CommonJS, conditional exports, browser/server splits, and bundler entry points

## Ignore

- File placement or naming preferences without a boundary consequence
- Demands for interfaces or generics where a concrete local type is clearer
- Compiler-enforced details with no architectural effect

## Applicability

Use this specialist when changed TypeScript affects package, workspace, module, dependency, or runtime ownership boundaries.

## Project-Specific Rules

### TypeScript Architecture Rules

- Verify `TypeScript module and dependency APIs` preserve ownership invariants; reject a dependency cycle or ownership boundary failure.
- Keep stable domain behavior independent of framework, database, transport, and browser or Node adapters where the repository establishes that boundary.
- Prevent barrel exports and path aliases from hiding cycles or exposing internal modules as public API.
- Keep compile-time-only dependencies out of runtime bundles and server-only modules out of browser graphs.
- Treat project references, package exports, code generation, decorators, and build plugins as architecture when they define ownership or emitted behavior.
- Avoid service locators, module singletons, and mutable global state that obscure request, test, or application lifecycle.
- Ensure shared types do not falsely imply shared runtime validation or leak persistence/transport shapes into domain ownership.
- Findings must identify the violated boundary and concrete compatibility, deployment, or maintenance cost.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
