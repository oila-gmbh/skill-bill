---
name: bill-php-code-review-platform-correctness
description: Use when reviewing PHP correctness risks in business logic, state transitions, retry or replay behavior, edge cases, and runtime safety.
internal-for: bill-code-review
---

# Platform-Correctness Review Specialist

Review only reachable correctness issues that change behavior, violate a declared contract, or make behavior unsafe.

Within the PHP package, `platform-correctness` is the package-aligned correctness lane. In practice, this specialist is primarily about backend business-logic correctness, state handling, retry/idempotency correctness, and runtime-safety issues in changed PHP code.

## Focus

- Race conditions, ordering bugs, and stale-state updates
- Nullability/edge-case failures and crash paths
- State-machine and contract handling correctness
- Business-rule drift in conditionals, refactors, and retries
- Violated invariants, missing guards, and wrong branch selection in business logic
- Partial-success or alternate-path behavior that no longer matches the declared contract
- Retry/replay and duplicate-delivery correctness

## Ignore

- Style or readability feedback without correctness impact

## Project-Specific Rules

### Shared Backend Correctness

- Distinguish absent vs null vs defaulted values when behavior changes
- Avoid loose comparison or implicit string/int/bool coercion in business decisions when values such as `"0"`, `0`, `false`, `null`, and `""` have different meanings
- Use `empty()` and `isset()` only when their collapsed semantics match the business rule; otherwise preserve missing, null, false, zero, and empty-string cases explicitly
- Keep dynamic arrays, decoded payloads, and array-shape assumptions validated before business logic depends on keys, nested values, or value types
- Shared mutable state must be synchronized, serialized, version-checked, or replaced with safer flow/message-driven coordination
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where retries, duplicate delivery, schedulers, or concurrent requests can race or overwrite each other
- Treat deprecated APIs or patterns as correctness findings only when they create runtime behavior, compatibility, security, lifecycle, or supportability risk; otherwise leave them to quality tooling or style review
- State transitions must preserve declared invariants and reject invalid intermediate states
- Time, timezone, and clock-boundary logic must be explicit where behavior depends on them

### Business Logic / Invariant Checks

- Guard ordering must preserve business-rule priority and must not make terminal, invalid, or exceptional states reachable as normal success paths
- Refactors, condition merges, and extracted helpers must not collapse previously distinct business cases into the same outcome unless the contract explicitly changed
- Absent vs null vs empty vs zero vs defaulted values must preserve their business meaning across validation, mapping, persistence, and response code
- Multi-step workflows must not persist state that contradicts the reported outcome or skip cleanup that the surrounding contract depends on
- One-time or prerequisite checks must still run on retry, replay, duplicate delivery, and alternate entry paths unless the contract explicitly permits bypassing them
- Feature-flag, permission-gated, and role-gated paths must preserve the same core invariants as the primary path unless different behavior is explicitly intended
- Ground potential edge-case findings in a reachable code path or declared contract by naming the triggering input, state, retry sequence, worker lifecycle, or boundary condition and the violated expected behavior
- For Blocker or Major correctness findings, include a concrete failure scenario that explains how the changed code can produce the wrong outcome

### Backend/Server-Specific Rules

- Message consumers, schedulers, and jobs must be safe under retry/replay; acknowledge or commit only after durable success
- Concurrent writes need atomic statements, locking, version checks, idempotency keys, or another explicit consistency mechanism
- External side effects must happen in the intended order relative to persistence and commit boundaries
- Retry-sensitive paths must not duplicate user-visible effects, billing effects, or event emission unless the contract explicitly permits it

### Error Handling

- Domain exceptions, client faults, and operational failures must not be caught and converted into misleading success or fallback behavior
- Catching broad `Throwable` or `Exception` must preserve the intended failure semantics through rollback, retry, reporting, or a declared fallback path
- Multi-step workflows must not report full success when only a partial effect was applied unless the contract explicitly permits partial success

### Runtime / Dispatch Semantics

- Queued/background work, event listeners, notifications, mail, and broadcast mechanisms must preserve the same correctness guarantees under retries and duplicate delivery
- Job dispatch, queue scheduling, and event emission must respect transaction semantics when the project expects after-commit behavior
- Long-running workers, daemons, and queue consumers must not reuse request-local state, mutable static state, stale service state, or ORM instances across messages unless that lifecycle is explicit and safe

### ORM / Boundary / Language-Behavior Checks

- ORM-backed model/entity state must not be reused across requests, messages, retries, workers, or alternate entry paths when stale attributes or loaded relations can change behavior
- Authorization checks, parameter binding/lookup, and boundary validation must not leave reachable paths with partially authorized or partially validated behavior
- Collection pipelines, nullable chains, and convenience helpers must not hide branch loss or silently swallow incorrect states
- Date casting, enum casting, and numeric/string coercion must not change business behavior unexpectedly
- If behavior depends on the current user, current time, locale, or timezone, make that dependency explicit and verify boundary cases
