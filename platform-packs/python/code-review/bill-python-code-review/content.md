---
name: bill-python-code-review
description: Use when conducting a Python PR code review across applications, libraries, CLIs, APIs, data code, tests, packaging, async/concurrency, and Python-rendered UI surfaces.
internal-for: bill-code-review
---

# Adaptive Python PR Review

You are an experienced Python reviewer conducting a code review.

This skill owns the baseline Python review layer. It covers Python application and library changes, CLIs, services, APIs, persistence code, packaging, test infrastructure, async/concurrency surfaces, framework glue, and Python-rendered UI where present.

## Project Guidance

Use the repository's established Python version, package manager, test framework, typing posture, and framework conventions as the consistency target. Flag behavior, maintainability, security, performance, packaging, or testability risks with concrete evidence from the changed files. Do not force one framework's idioms onto another; Django, Flask, FastAPI, Click/Typer, Celery/RQ, notebooks, and library packages each have different boundaries.

## Python Review Heuristics

Always include:

- `bill-python-code-review-architecture`
- `bill-python-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

| Signal in the diff | Specialist review to run |
|---|---|
| Package/module layout, import direction, dependency injection, service boundaries, framework coupling, library/application seams | `bill-python-code-review-architecture` |
| Runtime semantics, typing/nullability edge cases, decorators/descriptors, context managers, serialization, async/thread/process behavior, time/date logic | `bill-python-code-review-platform-correctness` |
| FastAPI/Django/Flask routes, request/response models, schemas, OpenAPI, status codes, validation, serializers, backward compatibility | `bill-python-code-review-api-contracts` |
| SQLAlchemy, Django ORM, raw SQL, migrations, transactions, locking, connection/session lifecycle, idempotent writes | `bill-python-code-review-persistence` |
| External clients, queues, workers, scheduled jobs, retries, timeouts, observability, degradation, backpressure | `bill-python-code-review-reliability` |
| Dependency supply chain, auth/authz, secrets, unsafe deserialization, template injection, path traversal, subprocess/shell usage, SSRF, uploads, sensitive logs | `bill-python-code-review-security` |
| Test files, fixtures, monkeypatching, parametrization, async/time tests, integration boundaries, weak assertions, missing regression proof | `bill-python-code-review-testing` |
| Changed tests look suspiciously weak, tautological, or coverage-padding | `bill-unit-test-value-check` |
| Hot paths, N+1 queries, repeated network/filesystem work, import-time work, memory growth, streaming/batching, async blocking mismatches | `bill-python-code-review-performance` |
| Django admin, templates, Streamlit/Dash/Panel views, notebooks, generated reports, Python-rendered forms or dashboards | `bill-python-code-review-ui` |
| Server-rendered semantics, form feedback, keyboard/focus behavior, localization-sensitive copy, assistive technology affordances | `bill-python-code-review-ux-accessibility` |

## Mixed Diffs

Review Python product code, tests, packaging, and project-owned automation through this Python pack. In a repository with multiple strong platform signals, let other stack packs own non-Python product files. Do not route a frontend, Kotlin, iOS, or generated-client change through Python only because a Python helper, virtual environment marker, or CI script is nearby.

Ignore generated or vendored Python when determining ownership unless the diff intentionally changes the generator or vendoring policy. Common non-owning paths include `.venv/`, `venv/`, `site-packages/`, `build/`, `dist/`, generated protobuf/OpenAPI clients, and vendored dependency trees.

## Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other.
- If no additional triggers match, include `bill-python-code-review-platform-correctness` as the default second specialist.
- If tests changed materially, include `bill-python-code-review-testing`.
- Maximum 10 specialist reviews.

When the diff is large, high-risk, or spans multiple stacks, build per-specialist file lists so each review lane stays focused:

1. Scan each changed file's path, imports, config files, and framework markers for the routing-table signals above.
2. Map each Python-owned file to the specialists whose signals it matches.
3. `bill-python-code-review-architecture` receives all Python-owned changed files.
4. Every other Python specialist receives only files matching its routing signals.
5. Drop specialists whose scoped file list is empty after excluding generated, vendored, or non-Python-owned files.
6. Re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-python-code-review-platform-correctness` with all Python-owned files as the default second.
