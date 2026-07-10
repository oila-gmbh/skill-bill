---
name: bill-python-code-review-architecture
description: Review Python architecture, package boundaries, dependency direction, configuration ownership, and application/library seams.
internal-for: bill-code-review
---

# Python Architecture Review

Review concrete ownership, dependency, lifecycle, and module-boundary failures.

## Focus

- Package boundaries, dependency direction, transaction and configuration ownership, service lifetimes, and application/library seams

## Ignore

- Generic layering preferences that conflict with the repository's established architecture without creating a concrete risk
- Layering purity for simple CRUD where an added abstraction would not reduce coupling or protect an invariant

## Applicability

Use this specialist across Python packages, libraries, applications, services, framework adapters, CLIs, and worker entry points. Infer the repository's local architecture first and treat it as authoritative before applying generic patterns.

## Project-Specific Rules

### Ownership and Dependency Direction

- Require one explicit transaction owner for each atomic business operation; reject nested or split ownership that changes commit, rollback, or after-commit ordering.
- Require explicit owners and lifetimes for clients, sessions, clocks, settings, feature flags, credentials, services, and dependency-injection containers; reject hidden globals or request-scoped objects captured by longer-lived services.
- Preserve package and module boundaries, intentional `__init__.py` exports, namespace-package behavior, and public APIs; reject import cycles or cross-layer dependencies that bypass the repository's chosen boundary.
- Keep Django, FastAPI, Flask, Celery, Click, and Typer types at the repository's intended edges unless local architecture explicitly makes them part of the core boundary.

### Composition and Shared Outputs

- Require reusable modules to remain import-safe and verify packaging metadata, entry points, optional dependencies, and side effects do not turn library imports into application startup.
- Reject abstraction layers that do not reduce coupling and duplicated orchestration that bypasses an existing shared helper or extension point.
- When multiple producers write a shared output contract, require one owner or a shared schema and compatibility path; reject producer-local changes that make consumers interpret the same output differently.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
