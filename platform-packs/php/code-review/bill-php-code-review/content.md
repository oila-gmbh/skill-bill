---
name: bill-php-code-review
description: Use when conducting a thorough PHP PR code review across backend, service, and server-rendered PHP code. Select specialists for architecture, correctness, API contracts, persistence, reliability, security, performance, testing, UI, and UX/accessibility based on changed-file signals.
internal-for: bill-code-review
---

# Adaptive PHP PR Review

You are an experienced PHP architect conducting a code review.

This skill owns the baseline PHP review layer. It covers backend/service PHP concerns, server-rendered UI surfaces, and the specialist-selection logic that the PHP review lanes build on top of.

## Project Guidance

Treat coherent local project standards and established architecture as the consistency target. Do not preserve local patterns that are inconsistent, accidental, or harmful; use this PHP pack to flag concrete maintainability, correctness, security, scalability, or testability risks and guide changes toward established PHP/backend practices.

## PHP Review Heuristics

Always include:

- `bill-php-code-review-architecture`
- `bill-php-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

| Signal in the diff                                                                                                                                                                     | Specialist review to run                    |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Layering changes, module ownership, ports/adapters, query services, read models, projections, outbox, listeners, projectors, boundary-crossing composition                            | `bill-php-code-review-architecture`         |
| Conditional logic, state transitions, retry-sensitive logic, time/date logic, nullability, behavior drift in refactors                                                                 | `bill-php-code-review-platform-correctness` |
| Routes/controllers/actions, requests, resources, serializers, status codes, OpenAPI/schema changes, validation/error payloads, server-rendered payload contracts                       | `bill-php-code-review-api-contracts`        |
| Repositories, ORM models, SQL, query builders, migrations, locking, transactions, projections, bulk writes                                                                             | `bill-php-code-review-persistence`          |
| Jobs, consumers, schedulers, retries, queues, caches, external clients, fallback behavior, logging/metrics/tracing                                                                     | `bill-php-code-review-reliability`          |
| Auth/authz, trust-boundary code, secrets, uploads, signed URLs, template rendering, JS or DOM injection risks, deserialization, sensitive logs, workflow or script credential handling | `bill-php-code-review-security`             |
| Test files changed, contract tests, deterministic retry/idempotency tests, weak/tautological tests, missing regression proof                                                           | `bill-php-code-review-testing`              |
| Changed tests look suspiciously weak, tautological, or coverage-padding                                                                                                                | `bill-unit-test-value-check`                |
| Hot paths, N+1, repeated downstream calls, serialization waste, feed/backfill loops, rendering waste, unbounded buffers or batch work                                                  | `bill-php-code-review-performance`          |
| Blade, Twig, Livewire, Inertia, Filament, form flows, component rendering, server-rendered UI state, interactive admin surfaces                                                        | `bill-php-code-review-ui`                   |
| Accessibility semantics, focus management, validation feedback UX, keyboard behavior, localization-sensitive UI copy, screen-reader affordances                                        | `bill-php-code-review-ux-accessibility`     |

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

## Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-php-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-php-code-review-testing`
- Maximum 10 specialist reviews

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's name and imports for the routing-table signals above.
2. Map each file to the specialists whose signals it matches.
3. `bill-php-code-review-architecture` always receives all changed files.
4. Every other specialist receives only files matching its routing signals.
5. If a non-architecture specialist's scoped file list is empty, drop it from the selected set.
6. After scoping, re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-php-code-review-platform-correctness` with all changed files as the default second.

This is a lightweight file-level classification, not a full review.
