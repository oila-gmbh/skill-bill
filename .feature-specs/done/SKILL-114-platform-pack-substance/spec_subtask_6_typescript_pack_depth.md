# SKILL-114 Subtask 6 - TypeScript Pack Depth

## Scope

Replace TypeScript's scaffold-heavy first version with expert TypeScript
guidance across all ten areas. Treat TypeScript as a compile-time type system
over multiple JavaScript runtimes and deployment targets; every rule must state
which runtime or framework condition makes it applicable.

Required depth includes narrowing/generics/variance/declaration and emitted-JS
boundaries, ESM/CJS/bundler/package exports, Node/Deno/Bun/browser/worker
runtime differences, promises/event loop/cancellation/streams, monorepo and
package architecture, schema/runtime validation and HTTP/RPC/event contracts,
ORM/query-builder/migration/transaction semantics, trust boundaries/XSS/CSRF/
prototype and supply-chain risks, unit/type/integration/browser tests,
retries/queues/shutdown/telemetry, TSX/framework rendering and hydration, and
web accessibility/localization.

Quality checking must derive package-manager/workspace commands and cover
format/lint/typecheck/build/test, browser/integration paths when configured,
package and declaration output, lock/dependency/security checks, target/module
matrices, and targeted-to-full escalation.

## Acceptance Criteria

1. All ten TypeScript specialists meet the substance gate and no rule uses a
   generic `` `TypeScript ... APIs` `` placeholder.
2. Correctness and architecture address type/runtime mismatches, narrowing and
   unsound escape hatches, emitted JavaScript, module/package/bundler/target
   behavior, and ownership across server/browser/worker boundaries.
3. Performance and reliability address event-loop blocking, promise/task
   fan-out, cancellation, streams/backpressure, rendering/bundles, queues,
   retries, process shutdown and telemetry with runtime-specific consequences.
4. API, persistence, security, and testing cover schema validation, wire/event
   compatibility, transaction/migration/client behavior, browser/server trust
   boundaries and supply chain, plus type/unit/integration/browser evidence.
5. UI and UX/accessibility deeply cover TSX and detected UI frameworks,
   state/effects/events/rendering/hydration, semantics, focus/keyboard, live
   feedback, localization and assistive technology without assuming React is
   universal.
6. The quality checker covers detected package/workspace commands plus format,
   lint, typecheck, build, tests, package/declaration output, dependency and
   module/target matrices.
7. TypeScript passes both duplication thresholds, including every
   TypeScript/Rust corresponding rubric.
8. Manifest metadata, agents, tests, and a new `agent/history.md` entry record
   the completed TypeScript boundary.
9. TypeScript pack tests, `skill-bill validate`, and relevant Gradle checks
   pass.

## Non-Goals

- No React-only definition of TypeScript UI.
- No assumption that compile-time types validate external data.
- No Rust content edits.

## Dependency Notes

Depends on subtask 1. Independent of other pack elevations.

## Validation Strategy

Run the maintained-pack audit for TypeScript, focused TypeScript pack tests,
`skill-bill validate`, and the relevant Gradle suite.

## Next Path

Proceed independently; subtask 10 waits for completion.
