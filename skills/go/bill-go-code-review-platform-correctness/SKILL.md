---
name: bill-go-code-review-platform-correctness
description: Use when reviewing behavior correctness, edge cases, goroutine/channel safety, context cancellation, error handling, and concurrency-sensitive logic in Go backend/service changes.
---

# Platform-Correctness Review Specialist

Review only correctness issues that change behavior or make behavior unsafe.

Within the Go package, `platform-correctness` is the package-aligned correctness lane. In practice, this specialist is
primarily about backend business-logic correctness, context/cancellation correctness, zero-value and nil handling,
concurrency safety, and runtime-safety issues in changed Go code.

## Focus
- Race conditions, ordering bugs, and stale-state updates
- Nil/zero-value edge cases, panic paths, and crash paths
- Context propagation, cancellation, and timeout correctness
- Business-rule drift in conditionals, refactors, retries, and state transitions
- Goroutine/channel lifetime correctness and duplicate-delivery safety

## Ignore
- Style or readability feedback without correctness impact

## Applicability

Apply shared backend correctness rules to all backend/service code. Apply the deeper concern-specific checks only when
the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-platform-correctness`
section, read that section and apply it as the highest-priority instruction for this skill. The matching section may
refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Correctness
- Distinguish absent vs zero vs nil values when behavior changes
- Shared mutable state must be synchronized, serialized, version-checked, or replaced with safer flow/message-driven coordination
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where retries, duplicate delivery, schedulers, or concurrent requests can race or overwrite each other
- Do not introduce deprecated APIs or patterns when a supported alternative exists; if usage is unavoidable, it must be narrowly scoped and explicitly justified
- State transitions must preserve declared invariants and reject invalid intermediate states
- Time, timezone, deadline, and clock-boundary logic must be explicit where behavior depends on them

### Go Runtime / Language-Behavior Checks
- Functions that depend on cancellation, deadlines, tracing, or auth context should accept `context.Context` explicitly and pass it through the call chain
- Do not stash mutable request context in structs or globals when explicit parameters are required for safe propagation
- Normal error handling should use returned errors, not `panic`, unless the contract is truly unrecoverable
- Check returned errors instead of discarding them; avoid misleading success paths after partial failure
- Wrapped errors should preserve actionable causes and be checked with `errors.Is`/`errors.As` or an equivalent chain-aware approach
- Channel ownership, close behavior, and send/receive coordination must make goroutine exits obvious and safe
- Goroutines must have clear owners, exit paths, and synchronization expectations; `WaitGroup`, semaphore, and channel usage should match the intended lifecycle exactly
- Concurrent access to maps, slices, caches, or shared structs must be synchronized or replaced with safer ownership patterns
- Be explicit about pointer aliasing, copying, and mutation when values cross goroutines or package boundaries
- Loop variables captured by goroutines, callbacks, or subtests must be copied explicitly so later iterations do not corrupt behavior
- `defer` inside loops or retry paths must not quietly accumulate cleanup, hold locks, or delay resource release longer than intended
- Timers, tickers, and `time.After`-style resources should be stopped or drained when lifetimes outlive one call path

### Backend/Server-Specific Rules
- Worker, consumer, and scheduler code must be safe under retry/replay; acknowledge or commit only after durable success
- Concurrent writes need atomic statements, locking, version checks, idempotency keys, or another explicit consistency mechanism
- External side effects must happen in the intended order relative to persistence and commit boundaries
- Retry-sensitive paths must not duplicate user-visible effects, billing effects, or event emission unless the contract explicitly permits it

### Error Handling
- Domain faults, client faults, and operational failures must not be collapsed into misleading success or generic fallback behavior
- Wrap or classify errors in ways that preserve the caller's ability to distinguish actionable cases
- Multi-step workflows must not report full success when only a partial effect was applied unless the contract explicitly permits partial success
- Deferred cleanup and rollback paths must not mask the primary failure or silently convert partial failure into apparent success

### Runtime / Dispatch Semantics
- Queued/background work, subscribers, timers, and HTTP handlers must preserve the same correctness guarantees under retries and duplicate delivery
- Shutdown, cancellation, and deadline handling must not leave orphaned goroutines or partially applied effects that callers believe succeeded
- If a spawned goroutine must not crash silently, recovery/logging belongs inside that goroutine; caller-side recovery does not protect it
- Build tags and platform-specific files should not hide changed production paths from CI or create inconsistent runtime behavior across the platforms the project claims to support

## Output Rules
- Report at most 7 findings.
- Include reproducible failure scenario for Major/Blocker findings.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
