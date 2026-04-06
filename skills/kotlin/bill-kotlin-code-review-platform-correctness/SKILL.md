---
name: bill-kotlin-code-review-platform-correctness
description: Use when reviewing lifecycle, coroutine, threading, and logic correctness risks in Kotlin code. Use when user mentions coroutine leak, race condition, dispatcher misuse, lifecycle-unsafe collection, or threading bug in Kotlin code.
---

# Platform & Correctness Review Specialist

Review only correctness and runtime-safety issues.

## Focus
- Coroutine scoping, cancellation, and dispatcher/thread correctness
- Race conditions, ordering bugs, and stale-state updates
- Nullability/edge-case failures and crash paths
- State-machine and contract handling correctness
- Business-rule drift in conditionals, reducers, refactors, and retries
- Violated invariants, missing guards, and wrong branch selection in changed logic
- Resource ownership and lifecycle safety where relevant

## Ignore
- Style or readability feedback without correctness impact

## Applicability

Use this specialist for shared Kotlin correctness risks across libraries, app layers, and backend services. Favor issues around ownership, concurrency, cancellation, and logic safety that remain meaningful regardless of platform.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review-platform-correctness` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Kotlin Correctness
- Never use `GlobalScope`
- Long-lived coroutine scopes must have an explicit owner and cancellation strategy
- Shared mutable state must be synchronized, serialized, or replaced with immutable/message-driven flow
- Cancellation and timeout behavior must be explicit around long-running or external operations
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where multiple async sources can race or overwrite each other
- Do not introduce deprecated APIs, components, or patterns when a supported alternative exists; if usage is unavoidable, it must be narrowly scoped and explicitly justified
- Work launched from callbacks, requests, or scheduled entry points must remain tied to an explicit owner or be delegated to a managed background component
- Flow/state transformations should stay deterministic and make source priority explicit when multiple async inputs can race
- Concurrent writes need atomic statements, locking, version checks, or another explicit consistency mechanism
- Do not hold scarce resources (locks, transactions, open streams, file handles) across remote calls or long waits unless the contract explicitly requires it
- Startup-owned or application-owned scopes must be cancelled cleanly during shutdown or cleanup

### Business Logic / Invariant Checks
- Guard ordering in `if`/`when`, reducers, and state transitions must preserve business-rule priority and reject invalid states before success paths
- Refactors, extracted helpers, and shared transformation pipelines must not collapse distinct business cases into the same outcome unless the contract explicitly changed
- Null, absent, empty, default, and sentinel values must preserve their business meaning across mapping, storage, transport, and UI state
- Partial-success, optimistic update, and rollback paths must not report durable success before the contract's required effect actually happens
- Retry, recollection, resubscription, or repeated lifecycle entry must not bypass one-time business checks or re-apply one-time user-visible effects unless the contract explicitly permits it
- Feature-flag, permission-gated, and role-gated paths must preserve the same core invariants as the primary path unless different behavior is explicitly intended

## Output Rules
- Report at most 7 findings.
- Include reproducible failure scenario for Major/Blocker findings.
- Potential edge-case findings must be grounded in a reachable code path or declared contract. Identify the triggering input, state, async event sequence, or lifecycle transition and the violated invariant or expected behavior.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.
