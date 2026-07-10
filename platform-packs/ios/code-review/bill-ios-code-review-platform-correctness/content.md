---
name: bill-ios-code-review-platform-correctness
description: Use when reviewing iOS unidirectional-data-flow correctness, main-thread discipline, Combine effect lifecycles, and reducer purity.
internal-for: bill-code-review
---

# Platform Correctness Review Specialist

Review only high-signal platform-correctness issues.

## Focus

- Store/Action/Environment (unidirectional data flow) correctness
- Main-thread discipline for state mutation and UI-facing output
- Combine effect cancellation and lifecycle discipline
- Reducer purity and side-effect placement
- Lifecycle-unsafe state capture across async boundaries

## Ignore

- Style preferences for effect composition with no correctness impact
- Micro-optimizations to reducer code with no behavior change

## Applicability

Use this specialist wherever the project's unidirectional-data-flow pattern (a `{Feature}Store`/`Action`/`Environment`-shaped store, or equivalent) drives feature state, and wherever Combine (or an equivalent reactive pipeline) issues effects that ultimately mutate state or update the UI.

## Project-Specific Rules

### Actor Isolation And Structured Concurrency

- UI-facing mutation must be verified as `@MainActor` isolated or explicitly transferred to the main actor; never assume an arbitrary callback, task, or publisher resumes on the main actor
- Values crossing task or actor boundaries must be safely `Sendable`, immutable, or actor-isolated; reject unchecked crossings that permit concurrent mutation
- View-lifetime asynchronous work must use structured, cancellable ownership such as `.task` or an explicitly cancelled task handle; never leak unstructured `Task` closures beyond view disappearance
- After an `await`, revalidate state and identity when actor reentrancy can make the pre-suspension decision stale
- `withCheckedContinuation` and `withCheckedThrowingContinuation` bridges must resume exactly once on every success, failure, cancellation, and early-return path; reject double-resume and never-resume paths

### Stores, Effects, And Lifecycle

- Reducers (the `Action`-handling function of a `Store`) should generally stay pure: given the current state and an action, they compute the next state and describe effects to run. But first infer whether this project's `Store` actually enforces reducer purity — some custom (non-TCA) stores deliberately allow synchronous state or view-model mutation inside `reduce`, and when that is the established, pervasive house pattern it is not a defect. Flag in-reduce side effects only when they cause a concrete, reachable problem (an ordering/reentrancy bug, a main-thread violation, unmanaged async work that leaks or races, or real external I/O) — and describe that consequence. A purity deviation that merely conforms to the codebase's intentional pattern with no wrong outcome is at most Minor, not a Major "purity violation"
- Effects that update state or drive UI must be delivered back to the store on the main thread (e.g. via a `.receive(on: mainScheduler)`-style operator); do not update `@Published`/store state from a background queue or an arbitrary Combine scheduler
- Every long-running or cancellable effect must be registered under an explicit `cancellableId` (or equivalent effect-identity token); effects without an identity cannot be cancelled and can leak or race with a later effect for the same logical operation
- Starting a new effect for an id that already has one in flight must explicitly cancel the prior effect first, unless the store intentionally allows concurrent effects for that id
- Effects must not capture stale state by value across an async boundary when the store's state can change before the effect resumes; re-read current state through the store/environment rather than trusting a closure-captured snapshot
- Side effects (networking, persistence, timers, notifications) belong in the `Environment`, invoked from effects the reducer returns — not called directly from views or from the reducer body
- Cancellation on view disappearance, feature teardown, or navigation-away must actually cancel in-flight effects tied to that feature's cancellable ids
- Coordinate or unit values conflated between normalized (0–1) and raw/pixel ranges — including omitted axis offsets — produce visibly wrong placement and are a correctness bug, not a rounding detail
- Inverted, redundant, or always-true/always-false guard and boolean logic makes a conditional behave opposite to its intent or go permanently dead; scrutinize refactored conditionals for this
- Force-unwrap or `.require()` applied to state that can legitimately be nil at runtime crashes the app instead of gracefully guarding the expected absent-state case
- `@Observable`-conforming state that a view creates and owns must be held with `@State`, never `let` or a plain stored property; without `@State`, SwiftUI can recreate the instance across a parent redraw and silently discard accumulated state
- Any iOS 26-only API (Liquid Glass `glassEffect`/`GlassEffectContainer`/`.glass`/`.glassProminent` button styles, or other iOS 26+-only surface) added to a diff must be gated behind `#available(iOS 26, *)` with a working fallback path, unless the project's minimum deployment target is already iOS 26+
- `.animation(_:value:)` must always specify a `value` argument; the value-less `.animation(_:)` overload is deprecated and animates every state change in scope, not just the one the diff intends to animate
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.

## Repo-Local Knowledge

Before finalizing findings, check whether the repo under review ships its own agent-knowledge docs (e.g. `.agents/skills/*/references/*.md` and a root `AGENTS.md`/`CLAUDE.md`). When present, read them and weigh any documented hard-rule violation (e.g. an explicitly required main-thread or cancellation discipline) as a high-confidence finding. This is a read-only lookup local to the repo under review — nothing from these documents is copied into skill-bill's own tree.
