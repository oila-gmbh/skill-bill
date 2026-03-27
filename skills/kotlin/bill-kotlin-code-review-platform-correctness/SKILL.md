---
name: bill-kotlin-code-review-platform-correctness
description: Use when reviewing lifecycle, coroutine, threading, and logic correctness risks in Kotlin code.
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
