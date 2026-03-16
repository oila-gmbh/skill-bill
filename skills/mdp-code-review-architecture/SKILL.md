---
name: mdp-code-review-architecture
description: Review architecture, boundaries, DI scopes, and source-of-truth consistency in Kotlin/Android changes.
---

# Architecture Review Specialist

Review only high-signal architectural issues.

## Focus
- Layer boundaries (presentation/domain/data)
- Dependency direction and module ownership
- Source-of-truth consistency and fallback correctness
- Sync/merge semantics, idempotency, and data ownership
- DI scope correctness and lifecycle-safe wiring

## Ignore
- Formatting/style-only comments
- Naming preferences without architectural impact
- Localization and string resource issues (owned by `mdp-code-review-ux-accessibility`)

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

## Project-Specific Rules

### Layer Boundaries
- Domain layer must be pure Kotlin — no Android framework imports
- Domain must not depend on data or presentation
- Feature modules must not depend on each other — shared code goes in core modules
- Module structure: `core:common`, `core:data`, `core:domain`, `core:presentation`, `feature:*`

### Repository Pattern
- Repositories never throw exceptions — return concrete types (Boolean, nullable, empty collections)
- Wrap all database/network operations in try-catch with logging
- Factory pattern: interface in `core/domain/.../di/`, impl in `core/data/.../repository/`
- Implementation uses `@AssistedInject`, factory bound in `FactoryModule.kt` with `@Provides`
- Inject factory (not repository) in ViewModels
- Bulk operations (`insertMany`/`updateMany`) over N individual calls
- Prefer meaningful implementation names over generic `*Impl` suffix

### Repository Resilience Contracts
- Repositories are failure boundaries — absorb infra/data errors, return stable outputs
- `Flow` APIs: catch and emit safe defaults per contract
  - Single entity: `Flow<T?>` → emit `null` on error
  - List: `Flow<List<T>>` → emit `emptyList()` on error
- `suspend` APIs: return concrete failure values (`false`, `null`, typed result), never throw
- No `printStackTrace` — use structured logging with context
- ViewModels must be able to consume repositories without defensive catches

### ViewModel Contracts
- Each screen has State/Event/Effect contract
- State: `StateFlow` (read-only outside ViewModel)
- Events: `SharedFlow`
- No string resources in ViewModels — emit effects describing what happened, UI decides strings
- No analytics in ViewModels — emit declarative effects (e.g., `Effect.OccurrenceSaved`), UI calls analytics
- Create state from data sources (`repository.observeX().map`), never `init { load() }`

### DI
- Hilt only, constructor injection exclusively
- `@HiltViewModel` with assisted injection
- Never pass ViewModels down the UI hierarchy

### Data Flow
- Pass IDs between screens, not full objects
- Single source of truth — derive state reactively
- No data duplication across layers

### Naming & Types
- Data models: `*DataModel`, not `*Dto`
- Never use `kotlin.Result` or `Any` type
- Use `DispatcherProvider` instead of `Dispatchers.*`
- Use `.orEmpty()` instead of `?: ""`
- GraphQL queries use fragments for repeated field sets

### DataStore Safety
- Never rename DataStore files without migration
- Mark removed proto fields as `reserved`

## Output Rules
- Report at most 7 findings.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
