---
name: bill-typescript-code-review-ui
description: Use when reviewing TypeScript and TSX state, events, forms, rendering, hydration, routing, and browser behavior.
internal-for: bill-code-review
---

# UI Review Specialist

## Focus

- State ownership, derived state, events, forms, validation, loading, and error states
- Rendering, hydration, server/client boundaries, routing, and browser lifecycle
- Effects, subscriptions, stale closures, async updates, and cleanup
- DOM APIs, event typing, user-controlled content, and runtime browser support

## Ignore

- Visual taste without a repository design or behavior contract
- Framework preference when existing conventions are coherent
- Accessibility-only findings that belong to the `ux-accessibility` specialist
- Security-only findings that belong to the `security` specialist

## Applicability

Use this specialist when changed TypeScript or TSX affects state, events, forms, rendering, hydration, routing, or browser lifecycle.

## Project-Specific Rules

### TypeScript UI Rules

- Verify `TypeScript UI state and rendering APIs` preserve interaction invariants; reject an observable interaction or rendering failure.
- Keep one source of truth for user-visible state and avoid copying props or server data into divergent local state.
- Do not use a TypeScript event assertion to hide a mismatch between the actual element and handler contract.
- Prevent stale promises and closures from overwriting newer actions; cancel or identify obsolete work.
- Clean up effects, observers, listeners, timers, streams, and subscriptions on dependency change and unmount.
- Keep server-only modules and secrets outside client bundles and preserve hydration-compatible initial output.
- Render untrusted content through safe framework paths and scrutinize raw HTML or URL sinks.
- Make loading, empty, validation, retry, disabled, and failure states reachable and recoverable.
- Findings must give the user-visible failure sequence and supported runtime.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
