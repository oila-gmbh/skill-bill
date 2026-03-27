---
name: bill-code-review-platform-correctness
description: Use when reviewing lifecycle, coroutine, threading, and logic correctness risks in Android, KMP, backend/server, and general Kotlin code.
---

# Platform & Correctness Review Specialist

Review only correctness and runtime-safety issues.

## Focus
- Coroutine scoping, cancellation, and dispatcher/thread correctness
- Race conditions, ordering bugs, and stale-state updates
- Nullability/edge-case failures and crash paths
- State-machine and contract handling correctness
- Resource ownership and lifecycle safety where relevant

## Ignore
- Style or readability feedback without correctness impact

## Applicability

Apply shared Kotlin correctness rules to all code. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### Shared Kotlin Correctness
- Never use `GlobalScope`
- Long-lived coroutine scopes must have an explicit owner and cancellation strategy
- Shared mutable state must be synchronized, serialized, or replaced with immutable/message-driven flow
- Cancellation and timeout behavior must be explicit around long-running or external operations
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where multiple async sources can race or overwrite each other
- Do not introduce deprecated APIs, components, or patterns when a supported alternative exists; if usage is unavoidable, it must be narrowly scoped and explicitly justified

### Android/KMP-Specific Rules

#### Dispatchers
- Never use `Dispatchers.IO`, `Dispatchers.Main`, etc. directly when the project standard is `DispatcherProvider`
- Check that injected `DispatcherProvider` is used consistently

#### Coroutine Scoping
- ViewModels use `viewModelScope`
- Fire-and-forget operations that must survive ViewModel clearing use `@ApplicationScope` or the project equivalent
- `LaunchedEffect` keys must be stable — avoid using full data objects as keys when a derived boolean or ID suffices

#### Flow & State
- Use `collectAsStateWithLifecycle()` in Compose, never `collectAsState()`
- `StateFlow` for UI state, `SharedFlow` for one-time events unless the project intentionally standardizes on another pattern
- Side effects emitted via `SharedFlow` must not be lost — verify collector is active
- Check for race conditions between auto-dismiss timers and user interactions

#### Flow Composition
- When combining multiple flows, define source priority explicitly (primary vs enrichment streams)
- Keep transformations pure and deterministic — no hidden fallback behavior
- Emit a complete sealed UI state (`Loading`, `Content`, `Error`, `Empty`) where the screen contract expects it
- Add `.catch { ... }` before terminal `.stateIn()` for transformation-level failures when the contract requires resilient UI state
- Verify: primary present + enrichment missing, primary missing, one stream fails

#### Lifecycle
- No Activity/Fragment references held in ViewModels or repositories
- `DisposableEffect` for cleanup of listeners/callbacks
- `rememberSaveable` for state surviving configuration changes

#### Error Handling
- Repositories should absorb infra exceptions per project contract so callers do not need defensive try/catch everywhere
- Log with context (include relevant IDs) using the project logger

### Backend/Server-Specific Rules
- Do not block event-loop/request threads with JDBC, file I/O, crypto, or HTTP calls unless explicitly shifted to a dedicated dispatcher/executor
- Request handlers should not launch untracked coroutines that outlive the request unless work is delegated to a managed background component
- Message consumers, schedulers, and jobs must be safe under retry/replay; acknowledge or commit only after durable success
- Do not hold database transactions open across remote network calls or long waits
- Concurrent writes need atomic statements, locking, version checks, or another explicit consistency mechanism
- Application/service scopes created at startup must be cancelled cleanly on shutdown
- Verify timeout/cancellation propagation on outbound calls so abandoned requests do not continue wasting resources

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
