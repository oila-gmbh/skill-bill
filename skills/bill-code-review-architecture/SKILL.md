---
name: bill-code-review-architecture
description: Review architecture, boundaries, DI scopes, and source-of-truth consistency in Kotlin Android, KMP, and backend/server changes.
---

# Architecture Review Specialist

Review only high-signal architectural issues.

## Focus
- Layer boundaries and dependency direction
- Module ownership and source-of-truth consistency
- Sync/merge semantics, idempotency, and data ownership
- DI scope correctness and lifecycle-safe wiring
- Separation between transport, domain, and persistence concerns

## Ignore
- Formatting/style-only comments
- Naming preferences without architectural impact
- Localization and string resource issues (owned by `bill-code-review-ux-accessibility`)

## Applicability

First determine whether the code under review is:
- Android/KMP UI or app-layer code
- KMP shared/common code
- Backend/server code
- Generic Kotlin library code

Apply the shared Kotlin rules to every review. Apply Android/KMP-only rules only when Android/KMP signals are present. Apply backend/server-only rules only when backend/server signals are present. If both appear in one change, review each changed area with the relevant rules instead of forcing one model onto the other.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### Shared Kotlin Architecture
- Keep domain/business logic independent from transport and storage concerns unless the project intentionally uses a simpler shape
- Dependencies must point inward toward stable business rules, not outward toward frameworks or concrete infra details
- Preserve a single source of truth for each piece of business state; avoid duplicated ownership across layers
- Do not leak framework-specific models across boundaries when that couples unrelated layers
- External systems (network, database, messaging, file system) should be behind explicit adapters or repository/client boundaries
- Prefer constructor injection and explicit dependencies over service locators or hidden globals
- Avoid `kotlin.Result` and `Any` in core architecture contracts unless the project explicitly standardizes on them

### Android/KMP-Specific Rules

#### Layer Boundaries
- Domain layer must be pure Kotlin — no Android framework imports
- Domain must not depend on data or presentation
- Feature modules must not depend on each other — shared code goes in core modules
- Module structure typically follows `core:common`, `core:data`, `core:domain`, `core:presentation`, `feature:*`

#### Repository Pattern
- Repositories are failure boundaries — absorb infra/data errors and return stable outputs per project contract
- Wrap database/network operations in explicit handling with structured logging
- Factory pattern, assisted injection, and `FactoryModule.kt` rules apply when the project already uses that pattern
- Inject factories rather than concrete repositories into ViewModels when that is the established project convention
- Bulk operations (`insertMany`/`updateMany`) over N individual calls
- Prefer meaningful implementation names over generic `*Impl` suffix when the codebase already distinguishes responsibilities

#### ViewModel Contracts
- Each screen should have a clear State/Event/Effect contract
- State uses read-only `StateFlow` outside the ViewModel
- Events/effects should avoid direct Android resource dependencies
- Derive state reactively from data sources rather than imperative `init { load() }` patterns when the screen is fundamentally state-stream driven

#### DI
- Hilt-specific rules apply only when Hilt is already the chosen DI framework
- `@HiltViewModel`, constructor injection, and assisted injection should be wired consistently when present
- Never pass ViewModels down the UI hierarchy

#### Data Flow
- Pass IDs between screens rather than full objects
- Shared KMP code should stay platform-neutral unless platform APIs are intentionally abstracted
- Use `DispatcherProvider` instead of direct `Dispatchers.*` when that is the project convention

#### Naming & Data Safety
- DataStore file renames require migration
- Removed proto fields should be marked `reserved`
- Use `.orEmpty()` instead of `?: ""` when consistent with project style and semantics

### Backend/Server-Specific Rules
- Controllers/routes/handlers stay thin: validate input, derive auth context, call a service/use case, map the response
- Do not embed business workflows directly inside controllers, request filters, or ORM entities
- Keep API DTOs, domain models, and persistence entities separate when shapes or lifecycle constraints differ
- Repository/DAO boundaries must not leak ORM sessions, transaction handles, or SQL-building details into higher layers unless that is an explicit architectural choice
- Transaction boundaries should be explicit and owned consistently; avoid splitting one business operation across multiple implicit transactions
- External clients (HTTP, gRPC, queues, third-party SDKs) should sit behind dedicated adapters/interfaces for testability and swapability
- Migration/schema changes must consider rollout compatibility, existing data, and mixed-version deployments when relevant
- Constructor injection is preferred, but the DI framework may be Spring, Koin, Dagger, Micronaut, or manual wiring; judge scope clarity, not framework choice itself
- Background jobs, consumers, and schedulers should call the same use cases/services as the synchronous entry points instead of duplicating business logic

## Output Rules
- Report at most 7 findings.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
