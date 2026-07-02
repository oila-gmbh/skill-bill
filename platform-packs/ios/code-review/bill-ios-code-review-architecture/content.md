---
name: bill-ios-code-review-architecture
description: Use when reviewing iOS architecture, dependency-injection scopes, composition-root wiring, and SPM package-boundary respect.
---

# Architecture Review Specialist

Review only high-signal architectural issues.

## Focus

- Dependency-injection scope and lifetime correctness
- Composition-root wiring and registration hygiene
- Local Swift Package Manager package-boundary respect
- Module ownership and source-of-truth consistency
- Cross-module coupling introduced by new dependencies

## Ignore

- Formatting, style, and framework-idiom differences without architectural impact
- Naming or pattern preferences without concrete risk

## Applicability

Use this specialist across app targets and local SPM packages when changed code can affect dependency wiring, module boundaries, or architectural consistency. First infer the architecture established in the changed area and adjacent modules; treat coherent local architecture as the contract for consistency. Where the local architecture is absent, weak, inconsistent, or accidental, guide changes toward established iOS/Swift practices through concrete risk-based findings, not terminology preference.

## Project-Specific Rules

- Dependency injection should use the project's established DI container rather than ad-hoc singletons, global statics, or hidden service locators
- Respect the container's declared scopes (for example: singleton/app-lifetime, per-flow/session-scoped, and transient/per-use scopes); a dependency registered at one scope must not be resolved or cached in a way that outlives or leaks across that scope
- New dependencies must be registered through the project's composition-root registration surface (e.g. a `DependencyContainer+Registrations.swift`-style extension), not constructed inline at call sites
- Never bypass the DI container to reach a concrete implementation directly when a declared protocol/provider exists for that dependency
- Local SPM packages must only be consumed through their declared public API; reaching into another package's internal types, or importing implementation details across a package boundary, is an architecture violation
- A new local SPM package or new cross-package dependency must have a clear, documented reason it does not already fit an existing package boundary
- New cross-boundary consumers should depend on a `Provider`-style protocol rather than a concrete type, so the composition root remains the single place that wires concrete implementations to their consumers
- Business workflows and invariants should stay independent of transport, rendering, and persistence details when complexity warrants that separation or the project architecture already requires it
- Dead or unused code (parameters, variables, functions, properties, imports, whole files) left behind after a refactor should be removed rather than committed as noise that hides an incomplete refactor
- Duplicated or competing logic (retry wrappers, validation, effects, business rules) copy-pasted across layers or stores invites divergent behavior; extract it into a shared helper rather than maintaining parallel copies
- State mutated directly inside a Combine operator (`sink`/`map`/`flatMap`) or otherwise outside the reducer breaks unidirectional flow; state changes should be routed through the store's send/reduce path
- UI-adjacent or utility code placed in the wrong module/package layer (UI leaking into a core/light package, or code pushed too low-level) breaks the intended dependency direction and grows the build graph unnecessarily
- Legacy Combine/callback-based store patterns (cancellables held in state, delay chains) used in place of the current Effect-driven architecture mix thread-safety concerns into the store and should migrate to the established pattern
- Report architecture issues only when the change creates or worsens concrete risk: maintainability loss, duplicated ownership, unclear scope lifetime, testability loss, or coupling that makes the package graph harder to reason about

## Repo-Local Knowledge

Before finalizing findings, check whether the repo under review ships its own agent-knowledge docs (e.g. `.agents/skills/*/references/*.md` and a root `AGENTS.md`/`CLAUDE.md`). When present, read them and weigh any documented hard-rule violation (e.g. an explicitly frozen composition-root convention or DI-scope rule) as a high-confidence finding. This is a read-only lookup local to the repo under review — nothing from these documents is copied into skill-bill's own tree.
