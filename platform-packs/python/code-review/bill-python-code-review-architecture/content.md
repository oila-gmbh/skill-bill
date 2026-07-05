---
name: bill-python-code-review-architecture
description: Review Python architecture, package boundaries, dependency direction, configuration ownership, and application/library seams.
internal-for: bill-code-review
---

# Python Architecture Review

Focus on whether the changed Python code fits the repository's intended structure.

## Review Focus

- Package and module boundaries: public APIs, internal modules, `__init__.py` exports, namespace packages, and whether imports reveal accidental coupling.
- Dependency direction: domain code should not depend on delivery frameworks, CLIs, database sessions, task queues, or environment-specific adapters unless the local architecture intentionally allows it.
- Dependency injection and configuration: review how settings, clients, sessions, clocks, feature flags, and credentials are passed; flag hidden globals that make behavior hard to test or override.
- Framework coupling: keep Django/FastAPI/Flask/Celery/Click/Typer-specific code at appropriate edges instead of leaking framework types through core logic.
- Library/application boundaries: check packaging metadata, entry points, optional dependencies, and whether reusable modules remain safe to import without side effects.
- Change shape: flag new abstraction layers that do not reduce coupling, and flag duplicated orchestration that should be a shared helper or existing extension point.

## Findings Standard

Report only concrete risks: import cycles, cross-layer dependencies, unstable public interfaces, misplaced configuration, side-effectful imports, or boundaries that will make testing, packaging, or future extension materially harder.
