---
name: bill-typescript-code-review
description: Use when reviewing TypeScript libraries, applications, services, Node or browser runtimes, APIs, persistence, tests, and TSX UI surfaces.
internal-for: bill-code-review
---

# Adaptive TypeScript PR Review

Review TypeScript against the repository's `tsconfig`, package manager, module system, runtime targets, framework conventions, generated-code boundaries, and public compatibility policy. A successful type check is evidence, not proof of runtime correctness: types disappear at I/O, serialization, reflection, and JavaScript interop boundaries.

## Classification Rules

- Always select `bill-typescript-code-review-architecture`.
- Select at least two and at most ten specialists.
- Use `bill-typescript-code-review-platform-correctness` as the default second lane when no narrower signal applies.
- Select testing for material behavior, configuration, type-level contract, or regression changes.
- Send untrusted input and runtime validation to security and API contracts as well as the behavior-owning lane.
- Treat `any`, unchecked assertions, non-null assertions, disabled strictness, and ignored diagnostics as risk signals only when they bypass a reachable contract.

## Diff-Signal Routing Table

| Signal in the diff | Specialist review to run |
|---|---|
| Package/module boundaries, dependency direction, project references, public abstractions | `bill-typescript-code-review-architecture` |
| Strictness, narrowing, generics, assertions, module resolution, Node/browser targets | `bill-typescript-code-review-platform-correctness` |
| Exported types, HTTP/RPC, schemas, serialization, events, compatibility | `bill-typescript-code-review-api-contracts` |
| ORM/query code, migrations, transactions, serialization, durable writes | `bill-typescript-code-review-persistence` |
| Promises, cancellation, retries, queues, timeouts, shutdown, observability | `bill-typescript-code-review-reliability` |
| Auth, secrets, untrusted input, DOM injection, process/file/network boundaries, dependencies | `bill-typescript-code-review-security` |
| Type tests, unit/integration/browser tests, mocks, timers, concurrency | `bill-typescript-code-review-testing` |
| Event-loop work, allocation, bundles, rendering, repeated I/O, hot paths | `bill-typescript-code-review-performance` |
| TSX, state, events, forms, routing, rendering, hydration | `bill-typescript-code-review-ui` |
| Semantics, keyboard/focus flow, accessible names, feedback, localization | `bill-typescript-code-review-ux-accessibility` |

## Mixed Diffs

Classify each changed file independently. Architecture receives every first-party TypeScript-owned file; other specialists receive only matching files. Exclude `node_modules/`, `dist/`, build and coverage output, generated clients, generated declarations, and non-TypeScript-owned files. Ambient `*.d.ts` declarations or TypeScript used only for build tooling do not make the repository TypeScript-owned. After exclusions, drop empty lanes and restore the minimum by assigning all TypeScript-owned files to platform-correctness when architecture would otherwise stand alone.

## Finding Discipline

Report evidence-backed defects or material risks introduced by the diff. Name the violated type, runtime, repository, API, or operational contract and the concrete failure. Do not report formatter output, subjective style, or unsafe syntax by keyword alone. Use the shared F-XXX Risk Register and canonical severities.
