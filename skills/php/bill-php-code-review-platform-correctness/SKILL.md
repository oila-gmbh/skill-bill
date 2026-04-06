---
name: bill-php-code-review-platform-correctness
description: Use when reviewing behavior correctness, edge cases, retry and idempotency behavior, ordering bugs, and concurrency-sensitive logic in PHP backend/server changes. Use when user mentions PHP correctness, state transitions, retry logic, idempotency, or ordering bugs in PHP.
---

# Platform-Correctness Review Specialist

Review only correctness issues that change behavior or make behavior unsafe.

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

## Applicability

Apply shared backend correctness rules to all backend/server code. Apply the deeper concern-specific checks only when the changed code uses those mechanisms.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-platform-correctness` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

### Shared Backend Correctness
- Distinguish absent vs null vs defaulted values when behavior changes
- Shared mutable state must be synchronized, serialized, version-checked, or replaced with safer flow/message-driven coordination
- Do not introduce silent fallback behavior that hides failures unless the contract explicitly requires it
- Validate ordering guarantees where retries, duplicate delivery, schedulers, or concurrent requests can race or overwrite each other
- Do not introduce deprecated APIs or patterns when a supported alternative exists; if usage is unavoidable, it must be narrowly scoped and explicitly justified
- State transitions must preserve declared invariants and reject invalid intermediate states
- Time, timezone, and clock-boundary logic must be explicit where behavior depends on them

### Business Logic / Invariant Checks
- Guard ordering must preserve business-rule priority and must not make terminal, invalid, or exceptional states reachable as normal success paths
- Refactors, condition merges, and extracted helpers must not collapse previously distinct business cases into the same outcome unless the contract explicitly changed
- Absent vs null vs empty vs zero vs defaulted values must preserve their business meaning across validation, mapping, persistence, and response code
- Multi-step workflows must not persist state that contradicts the reported outcome or skip cleanup that the surrounding contract depends on
- One-time or prerequisite checks must still run on retry, replay, duplicate delivery, and alternate entry paths unless the contract explicitly permits bypassing them
- Feature-flag, permission-gated, and role-gated paths must preserve the same core invariants as the primary path unless different behavior is explicitly intended

### Backend/Server-Specific Rules
- Message consumers, schedulers, and jobs must be safe under retry/replay; acknowledge or commit only after durable success
- Concurrent writes need atomic statements, locking, version checks, idempotency keys, or another explicit consistency mechanism
- External side effects must happen in the intended order relative to persistence and commit boundaries
- Retry-sensitive paths must not duplicate user-visible effects, billing effects, or event emission unless the contract explicitly permits it

### Error Handling
- Domain exceptions, client faults, and operational failures must not be caught and converted into misleading success or fallback behavior
- Multi-step workflows must not report full success when only a partial effect was applied unless the contract explicitly permits partial success

### Runtime / Dispatch Semantics
- Queued/background work, event listeners, notifications, mail, and broadcast mechanisms must preserve the same correctness guarantees under retries and duplicate delivery
- Job dispatch, queue scheduling, and event emission must respect transaction semantics when the project expects after-commit behavior

### ORM / Boundary / Language-Behavior Checks
- ORM-backed model state must not be reused in ways that depend on stale loaded relations, stale attributes, or hidden implicit reload assumptions
- Authorization checks, parameter binding/lookup, and boundary validation must not leave reachable paths with partially authorized or partially validated behavior
- Feature-flag, permission-gated, and role-gated paths must preserve the same core invariants as the primary path unless the contract explicitly defines different behavior
- Collection pipelines, nullable chains, and convenience helpers must not hide branch loss or silently swallow incorrect states
- Date casting, enum casting, and numeric/string coercion must not change business behavior unexpectedly
- If behavior depends on the current user, current time, locale, or timezone, make that dependency explicit and verify boundary cases

## Output Rules
- Report at most 7 findings.
- Include reproducible failure scenario for Major/Blocker findings.
- Potential edge-case findings must be grounded in a reachable code path or declared contract. Identify the triggering input, state, or event sequence and the violated invariant or expected behavior.
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
