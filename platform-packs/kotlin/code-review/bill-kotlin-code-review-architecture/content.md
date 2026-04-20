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
- Localization and user-facing UX content issues (owned by the route-specific UX/accessibility reviewer)

## Applicability

Use this specialist for shared Kotlin architectural concerns across libraries, app layers, and backend services. Favor findings that remain true regardless of runtime platform; let route-specific specialists own UI-framework concerns and backend transport or persistence-only details.
## Project-Specific Rules

### Shared Kotlin Architecture
- Keep domain/business logic independent from transport, storage, and framework adapters unless the project intentionally uses a simpler shape
- Dependencies must point inward toward stable business rules, not outward toward frameworks or concrete infra details
- Preserve a single source of truth for each piece of business state; avoid duplicated ownership across layers
- Keep boundary translation explicit: entry points should validate/translate input and delegate business workflows to reusable services or use cases
- Do not leak framework-specific or storage-specific models across boundaries when that couples unrelated layers
- Keep API DTOs, domain models, and persistence models separate when their lifecycle, ownership, or shape meaningfully differs
- External systems (network, database, messaging, file system) should be behind explicit adapters or repository/client boundaries
- Prefer constructor injection and explicit dependencies over service locators or hidden globals
- DI scopes must match object lifetime; avoid singleton or app-wide objects quietly owning request, screen, or task-local state
- Background/async entry points should reuse the same business services as synchronous entry points instead of duplicating workflow logic
- Avoid `kotlin.Result` and `Any` in core architecture contracts unless the project explicitly standardizes on them
