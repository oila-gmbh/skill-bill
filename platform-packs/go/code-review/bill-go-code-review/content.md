---
name: bill-go-code-review
description: Use when conducting a Go PR code review across services, CLIs, APIs, workers, tests, modules, concurrency, persistence, and Go-rendered UI surfaces.
internal-for: bill-code-review
---

# Adaptive Go PR Review

You are an experienced Go reviewer conducting a code review.

This skill owns the baseline Go review layer. It covers Go services, libraries, CLIs, APIs, workers, persistence code, module/package structure, concurrency surfaces, and Go-rendered UI where present.

## Project Guidance

Use the repository's established Go version, module/workspace layout, framework choices, test style, error-handling posture, and concurrency conventions as the consistency target. Flag behavior, maintainability, security, performance, packaging, or testability risks with concrete evidence from changed files. Do not force one framework's idioms onto another; standard-library HTTP, chi, Gin, Echo, gRPC, Cobra, sqlc, GORM, Ent, and worker/service packages each have different boundaries.

## Go Review Heuristics

Always include:

- `bill-go-code-review-architecture`
- `bill-go-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

| Signal in the diff | Specialist review to run |
|---|---|
| Package layout, module/workspace boundaries, import direction, dependency injection, service boundaries, interface ownership, framework coupling | `bill-go-code-review-architecture` |
| Runtime semantics, nil/interface edge cases, errors, defer/panic/recover behavior, goroutines, channels, context cancellation, time/date logic | `bill-go-code-review-platform-correctness` |
| HTTP handlers, gRPC/protobuf/OpenAPI, request/response DTOs, JSON tags, validation, status codes, backward compatibility | `bill-go-code-review-api-contracts` |
| `database/sql`, sqlc, GORM, Ent, migrations, transactions, locking, connection lifecycle, idempotent writes | `bill-go-code-review-persistence` |
| External clients, queues, workers, schedulers, retries, timeouts, context propagation, observability, degradation, backpressure | `bill-go-code-review-reliability` |
| Auth/authz, secrets, unsafe parsing, path traversal, subprocess/shell usage, SSRF, uploads, templates, sensitive logs | `bill-go-code-review-security` |
| Test files, table tests, fixtures, fakes, golden files, race-sensitive tests, integration boundaries, weak assertions, missing regression proof | `bill-go-code-review-testing` |
| Changed tests look suspiciously weak, tautological, or coverage-padding | `bill-unit-test-value-check` |
| Hot paths, allocations, reflection, goroutine leaks, unbounded buffers, N+1 queries, repeated network/filesystem work, serialization waste | `bill-go-code-review-performance` |
| `html/template`, `text/template`, htmx-style fragments, admin pages, generated reports, terminal UI, or dashboards | `bill-go-code-review-ui` |
| Server-rendered semantics, form feedback, keyboard/focus behavior, localization-sensitive copy, assistive technology affordances | `bill-go-code-review-ux-accessibility` |

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

## Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-go-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-go-code-review-testing`
- Maximum 10 specialist reviews

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's path, imports, generated-code markers, module metadata, and framework markers for the routing-table signals above.
2. Map each Go-owned file to the specialists whose signals it matches.
3. `bill-go-code-review-architecture` receives all Go-owned changed files.
4. Every other Go specialist receives only files matching its routing signals.
5. Drop specialists whose scoped file list is empty after excluding generated, vendored, or non-Go-owned files.
6. Re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-go-code-review-platform-correctness` with all Go-owned files as the default second.

This is a lightweight file-level classification, not a full review.
