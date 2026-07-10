---
name: bill-php-code-review
description: Use when conducting a thorough PHP PR code review across backend, service, and server-rendered PHP code. Select specialists for architecture, correctness, API contracts, persistence, reliability, security, performance, testing, UI, and UX/accessibility based on changed-file signals.
internal-for: bill-code-review
---

# Adaptive PHP PR Review

You are an experienced PHP architect conducting a code review.

This skill owns the baseline PHP review layer. It covers backend/service PHP concerns, server-rendered UI surfaces, and the specialist-selection logic that the PHP review lanes build on top of.

## Classification Rules

Treat coherent local project standards and established architecture as the consistency target. Do not preserve local patterns that are inconsistent, accidental, or harmful; use this PHP pack to flag concrete maintainability, correctness, security, scalability, or testability risks and guide changes toward established PHP/backend practices.

If Composer metadata, PHP source, PHP tests, or PHP framework configuration dominate the changed product surface, classify the diff as PHP-owned and apply this pack.

Otherwise, do not classify the diff as PHP-owned when PHP appears only as tooling or CI glue around another dominant stack, or when the PHP signals come only from generated or vendored files.

Always include:

- `bill-php-code-review-architecture`
- `bill-php-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

### Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-php-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-php-code-review-testing`
- Maximum 10 specialist reviews

## Diff-Signal Routing Table

- Layering changes, module ownership, ports/adapters, query services, read models, projections, outbox, listeners, projectors, boundary-crossing composition -> `architecture` specialist.
- Conditional logic, state transitions, retry-sensitive logic, time/date logic, nullability, behavior drift in refactors -> `platform-correctness` specialist.
- Routes/controllers/actions, requests, resources, serializers, status codes, OpenAPI/schema changes, validation/error payloads, server-rendered payload contracts -> `api-contracts` specialist.
- Repositories, ORM models, SQL, query builders, migrations, locking, transactions, projections, bulk writes -> `persistence` specialist.
- Jobs, consumers, schedulers, retries, queues, caches, external clients, fallback behavior, logging/metrics/tracing -> `reliability` specialist.
- Auth/authz, trust-boundary code, secrets, uploads, signed URLs, template rendering, JS or DOM injection risks, deserialization, sensitive logs, workflow or script credential handling -> `security` specialist.
- Test files changed, contract tests, deterministic retry/idempotency tests, weak/tautological tests, missing regression proof -> `testing` specialist.
- Changed tests look suspiciously weak, tautological, or coverage-padding -> `bill-unit-test-value-check`.
- Hot paths, N+1, repeated downstream calls, serialization waste, feed/backfill loops, rendering waste, unbounded buffers or batch work -> `performance` specialist.
- Blade, Twig, Livewire, Inertia, Filament, form flows, component rendering, server-rendered UI state, interactive admin surfaces -> `ui` specialist.
- Accessibility semantics, focus management, validation feedback UX, keyboard behavior, localization-sensitive UI copy, screen-reader affordances -> `ux-accessibility` specialist.

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's path, imports, generated-code markers, Composer metadata, and framework markers for the routing-table signals above.
2. Map each PHP-owned file to the specialists whose signals it matches.
3. `bill-php-code-review-architecture` receives all PHP-owned changed files and stays selected for the whole review.
4. Every other PHP specialist receives only files matching its routing signals.
5. Drop specialists whose scoped file list is empty after excluding generated, vendored, or non-stack (non-PHP-owned) files.
6. After scoping, re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-php-code-review-platform-correctness` with all PHP-owned files as the default second.

This is a lightweight file-level classification, not a full review.

- Load and execute each selected specialist's governed rubric so every selected lane produces an attributed result.
- When the selected lanes exceed the runtime's available delegated-worker capacity, run them in deterministic waves while retaining every selected result.

## Finding Discipline

- Calibrate severity to concrete production, client, operator, or user impact using only the governed severity vocabulary.
- Verify each triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
