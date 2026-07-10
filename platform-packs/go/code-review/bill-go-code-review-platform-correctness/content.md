---
name: bill-go-code-review-platform-correctness
description: Use when reviewing Go correctness risks in business logic, state transitions, goroutines, channels, context cancellation, error handling, nil/zero-value edge cases, retry or replay behavior, and runtime safety.
internal-for: bill-code-review
---

# Platform-Correctness Review Specialist

Review only reachable correctness issues that change behavior, violate a declared contract, or make behavior unsafe.

Within the Go package, `platform-correctness` is the language and runtime correctness lane. In practice, this specialist is primarily about backend business-logic correctness, context propagation, zero-value and nil handling, error flow, goroutine/channel safety, retry/idempotency correctness, and runtime-safety issues in changed Go code.

## Focus

- Race conditions, ordering bugs, and stale-state updates
- Nil/zero-value edge cases and crash paths
- State-machine and contract handling correctness
- Business-rule drift in conditionals, refactors, and retries
- Violated invariants, missing guards, and wrong branch selection in business logic
- Partial-success or alternate-path behavior that no longer matches the declared contract
- Retry/replay and duplicate-delivery correctness
- Goroutine lifecycle, channel ownership, and context cancellation correctness

## Ignore

- Style or readability feedback without correctness impact

## Project-Specific Rules

### Shared Backend Correctness

- Distinguish absent vs zero vs nil values when behavior changes
- Keep decoded payloads, maps, and struct assumptions validated before business logic depends on keys, nested values, or value types
- Shared mutable state must be synchronized, serialized, version-checked, or replaced with safer flow/message-driven coordination
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where retries, duplicate delivery, schedulers, or concurrent requests can race or overwrite each other
- Treat deprecated APIs or patterns as correctness findings only when they create runtime behavior, compatibility, security, lifecycle, or supportability risk; otherwise leave them to quality tooling or style review
- State transitions must preserve declared invariants and reject invalid intermediate states
- Time, timezone, and clock-boundary logic must be explicit where behavior depends on them

### Business Logic / Invariant Checks

- Guard ordering must preserve business-rule priority and must not make terminal, invalid, or exceptional states reachable as normal success paths
- Refactors, condition merges, and extracted helpers must not collapse previously distinct business cases into the same outcome unless the contract explicitly changed
- Nil vs zero vs empty vs defaulted values must preserve their business meaning across validation, mapping, persistence, and response code
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

- Domain faults, client faults, and operational failures must not be collapsed into misleading success or generic fallback behavior
- Normal error handling should use returned errors, not `panic`, unless the contract is truly unrecoverable
- Check returned errors instead of discarding them; avoid misleading success paths after partial failure
- Wrapped errors should preserve actionable causes and be checked with `errors.Is`/`errors.As` or an equivalent chain-aware approach
- Multi-step workflows must not report full success when only a partial effect was applied unless the contract explicitly permits partial success

### Runtime / Dispatch Semantics

- Queued/background work, subscribers, timers, and HTTP handlers must preserve the same correctness guarantees under retries and duplicate delivery
- Shutdown, cancellation, and deadline handling must not leave orphaned goroutines or partially applied effects that callers believe succeeded
- If a spawned goroutine must not crash silently, recovery/logging belongs inside that goroutine; caller-side recovery does not protect it
- Build tags and platform-specific files should not hide changed production paths from CI or create inconsistent runtime behavior across the platforms the project claims to support

### Go Runtime / Language-Behavior Checks

- Functions that depend on cancellation, deadlines, tracing, or auth context should accept `context.Context` explicitly and pass it through the call chain
- Do not stash mutable request context in structs or globals when explicit parameters are required for safe propagation
- Channel ownership, close behavior, and send/receive coordination must make goroutine exits obvious and safe
- Goroutines must have clear owners, exit paths, and synchronization expectations; `WaitGroup`, semaphore, and channel usage should match the intended lifecycle exactly
- Concurrent access to maps, slices, caches, or shared structs must be synchronized or replaced with safer ownership patterns
- Be explicit about pointer aliasing, copying, and mutation when values cross goroutines or package boundaries
- Loop variables captured by goroutines, callbacks, or subtests must be copied explicitly so later iterations do not corrupt behavior
- `defer` inside loops or retry paths must not quietly accumulate cleanup, hold locks, or delay resource release longer than intended
- Timers, tickers, and `time.After`-style resources should be stopped or drained when lifetimes outlive one call path
- Authorization checks, parameter binding/lookup, and boundary validation must not leave reachable paths with partially authorized or partially validated behavior
- Date, enum, and numeric/string conversions must not change business behavior unexpectedly
- If behavior depends on the current user, current time, locale, or timezone, make that dependency explicit and verify boundary cases
