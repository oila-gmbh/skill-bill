# Reference: TypeScript Backend Architecture, Patterns & Review Signals

> Source knowledge for SKILL-116. Distilled from a mature, production TypeScript
> backend (NestJS monolith serving a web GraphQL API, an offline-first mobile
> GraphQL API, and a public REST API) plus an analysis of ~100 of its pull-request
> reviews. **Sanitized**: no company/project name, no repository name, no developer
> usernames, no PR numbers, no internal ticket IDs. This artifact is background
> knowledge for authoring pack content; it is not itself pack content and must not
> be copied verbatim into specialists or addons. Every rule derived from it must be
> restated generically, tied to a runtime/framework condition, and expressed as a
> concrete failure mode.

---

## Part A — Architecture & patterns

### A0. Stack shape (the archetype these patterns assume)
- Node.js + a decorator-based DI framework (NestJS-style: `@Module`, `@Injectable`, constructor injection), TypeScript.
- **Three client surfaces on one codebase:** (1) a web GraphQL API (`type-graphql`/schema-first), (2) a mobile "offline-first" GraphQL API with sync semantics (operations conventionally prefixed, e.g. `OF_*`), (3) a public REST API (OpenAPI/Swagger, API-key auth) for customer integrations.
- PostgreSQL via an Objection/Knex-style ORM + query builder; migrations; first-class soft-delete.
- Async: SQS (direct + event-bus-routed FIFO queues), a Redis-backed job library, in-process domain events (a pubsub library), cross-service events over an event bus (EventBridge-style).
- Search/AI: OpenSearch, a vector DB (Pinecone-style), an LLM gateway (Azure/OpenAI), feature flags (LaunchDarkly-style).
- Deploy: separate HTTP/API and worker services + scheduled one-shot tasks.
- Supply-chain guard: raw `npm install/ci` blocked by a `preinstall` guard; installs route through a firewall wrapper.

### A1. Macro split: new modular architecture vs legacy
- A target `modules/` tree (all new code) and a legacy tree being migrated out. A single sanctioned "legacy bridge" provider is the only way new code reaches legacy services; its use is contained and treated as migration debt. Migration is tracked as a governed checklist, not aspirational.

### A2. Module anatomy (Clean Architecture + intent-based CQRS)
```
<module>/
  commands/   state-changing use-cases: <verb>.command.ts + <verb>.handler.ts
  queries/    read use-cases: <verb>.query.ts + <verb>.handler.ts
  domain/     PURE logic — entity interfaces, validators, rules, errors, enums. NO deps.
  infra/db/   ORM models + repositories (transaction host, soft-delete query builder)
  infra/schema/  GraphQL resolvers, inputs (DTOs), output types
  providers/  injectable domain services, permission providers, pure util functions
  __tests__/  integration tests, fixtures, operation helpers
  index.ts    module wiring + selective exports
```
Older modules use a `usecases/` folder (`*Usecase`); newer ones split `commands/`+`queries/` (`*Handler`). Explicitly **"not full CQRS"** (no separate read/write models, no event sourcing) — just intent-based folder organization.

### A3. The strict dependency rule (the spine)
```
infra            → domain                     ✅
domain           → infra / providers          ❌ (domain stays pure, zero deps)
commands/queries → domain, infra, providers   ✅ (handlers orchestrate)
providers        → domain, infra              ✅ (domain services may use infra)
```
Keeps `domain/` unit-testable without mocks; lets infra be swapped; forces business rules explicit and pure.

### A4. `domain/` vs `providers/` decision
One question: does this logic need dependencies (DB, external API, config, other services)? **No → `domain/` pure function. Yes → `providers/` injectable domain service.**

### A5. Layer contract
- **Resolvers/controllers are thin adapters** — map DTO → command/query, call a handler; never inject repositories. Simple field resolvers (parent already authorized) may call a provider directly.
- **Handlers coordinate**; one per business action; internal flow: **authorize once early → business logic → persistence.**
- **Handlers never call other handlers** — shared side-effects go into a provider reused by handlers. Handler→handler double/wrongly authorizes and hides coupling.
- **Repositories are data-access only** — no business logic, no authorization, no god methods; use a transaction host + soft-delete query builder; return domain interfaces (not ORM models); throw domain-specific errors. Twin retrieval: `findX` → `null` (absence valid) vs `getX` → throws (absence is a bug). Explicit `new Date()` timestamps; `returning('*')`.
- **Commands/queries** are immutable (`readonly`) inputs validated with `class-validator`; implement `Pick<Entity, ...>`.
- **DI philosophy:** inject concrete classes, not interface tokens (use-cases are ~1:1 with implementation; testing is integration-first).
- **Execution-context pattern:** handlers inject a request-scoped execution context (`user`, etc.) rather than threading a `Context` param.

### A6. Cycle-breaking sub-modules (instead of `forwardRef`)
DI-framework boot fails on circular module deps. The sanctioned fix is **not** `forwardRef` (masks + spreads cycles) but extracting a smaller cycle-free surface:
- **`*Core`** (tier 1) — leaf primitives: repositories + pure read providers, **no auth, no business logic**, imports only infra + other `*Core`. Importable anywhere.
- **`*Shared`** (tier 2) — auth-aware handlers/orchestration depending on the permission module + `*Core`.
- Raw repositories must **not** be exported across module boundaries.

### A7. Permissions / authorization
- Central permission module talks to an external IAM service + caches; you evaluate a permission object built by a typed helper.
- Each module exposes a **permission provider** (`canCreate/canRead/canUpdate/canDelete`); cross-module auth = import the other module and **ask its provider** ("ask, don't tell").
- Permission checks **always** go through a provider — never inline, never static. Auth-z happens once, early, in the handler, enforced regardless of entry point (GraphQL/REST/queue).
- REST distinguishes **401 (unauthenticated)** vs **403 (authenticated but forbidden)**.

### A8. Async & events
- In-process domain events (pubsub library); cross-service events over an event bus → FIFO SQS (ordering via message-group IDs).
- Two queue kinds: direct and event-bus-routed. Worker consumers extend a base controller with a decorator.
- **Silent privileged-identity footgun:** SQS messages without an actor fall back to a system identity with full privileges — intentional for system events, but producers that should carry a user must attach one explicitly.

### A9. Offline-first (mobile) sub-system
- Convention prefix (`OF_*`) on all types/ops. Sync model: last-sync timestamp + cursor pagination + `since`; response shaped `created[]/updated[]/deleted[]`. Conflict resolution = offline overwrites server.
- **`problems` vs `errors`:** recoverable, strictly-typed sync issues (stale data, permission changed, parent deleted, conflict) returned inside `data.problems` (not transport `errors`), so a client self-heals a slice without failing the whole request.
- **Idempotency mandatory:** create/update retry-safe, revert into each other rather than throw; log such incidents. Prefer client-provided IDs.
- Accept client `createdAt/updatedAt` but always stamp server `serverCreatedAt/serverUpdatedAt` (used for ordering + conflict detection). Synced models want a composite index on `(tenantScope, serverUpdatedAt, id)`.
- **Mappers only** (`toOF_*`/`fromOF_*`); **no type casting** (`as`) — explicit mappers.

### A10. Public REST API conventions
- Controllers extend a shared base (enforces interceptors/pipes). Resource-oriented routing (`/api/v1/collection/:id/subcollection/:id`).
- **Uniform envelope** `{ message, data }`; **no `204`** — empty result returns `data: undefined`; enforced by a response interceptor + global error filter/mapper.
- **Cursor pagination contract:** `{ order_by, order_direction, after, limit }` → `{ after, count, total, items }`.
- **Public naming is a contract** (SDK generation): controller method names user-visible + globally unique; user terms not internal terms.
- **External source id:** resources carry an optional `externalSourceId` (exposed as `source_id`) for customer record correlation. A request-id header is echoed for tracing.

### A11. Paginated-query hardening (all surfaces)
A paginated handler is a boundary hit by many callers, so:
1. **`orderBy` must be an allowlist enum** (one source of truth); if a mapper produces the column, type the mapper against the same enum.
2. **Escape `LIKE`/`ILIKE` wildcards** in `searchTerm`; an unescaped `%`/`_` matches everything (full scan / logic error).

### A12. Multi-tenancy & DI-scope safety (machine-enforced)
- Custom lint rules ship as **errors**: (a) no mutable `this.*` state on a default-singleton `@Injectable()` provider; (b) no mutable module-level `let`/`var`/`new Map()`/`Set()`/literal mutated from a function.
- Reasoning: `@Injectable()` defaults to a **process-wide singleton**; **DI scope propagates up, not down**, so a provider injecting a request-scoped dependency becomes request-scoped itself — and any long-lived mutable state shared across concurrent requests bleeds across tenants. Fixes: request-scope the state, keep providers stateless, or use per-call instances. Alternative to per-call threading: `AsyncLocalStorage` (CLS) at middleware level.
- Bounded fan-out via a concurrency limiter (e.g. `p-limit`) so one message can't fire thousands of concurrent external calls.
- Note: `no-explicit-any` and several strictness rules are **off** (tech debt) — `any` is tolerated by the linter but caught in human review.

### A13. Testing philosophy (integration-first)
- Test through the real entry point (GraphQL over HTTP, queue consumer `handleMessage()`, domain-event subscriber). Real Postgres, real DI container.
- **Never mock owned application code** (providers/services/repositories/usecases). **Mock only external boundaries** (3rd-party HTTP via nock, IAM/permissions, event bus, queue producers, object storage, feature flags).
- **Never insert into the DB directly** for setup — build state through public operation helpers.
- **Name tests after behavior**, not code. **Assert on outcomes** (response, emitted events, queued messages), never on internal calls/wiring.
- Mock the in-process pubsub at the top of each test file to stop event cascade; test subscribers via their own entry point.
- Unit tests only for pure logic (validators, mappers, calculations). "If you need 3+ mocks, write an integration test." Tests live inside each module.

### A14. Errors, logging, config
- Domain errors extend the most specific shared base (`NotFoundEntityError`, `DeletedEntityError`, `ModuleError`, `InsufficientPermissionError`…), carry semantic meaning, JSDoc'd.
- Inject a shared logger; **redact sensitive data from logs**; log idempotency/retry incidents to the observability platform.
- Config via a config module + env-file per environment.

### A15. Specialized subsystems (opt-in territory)
- **Reports:** worker-orchestrated Handlebars-template → PDF pipeline with side-by-side version migration.
- **Integrations framework:** provider classes extend an abstract base + implement an interface, selected via a factory switch on an enum; provider-specific logic isolated in `providers/`.
- **AI/LLM & agentic assistant:**
  - **Model gateway pattern** — a single entry point for all model access: provider selection + per-user model routing via flags, a **circuit breaker** (N consecutive 5xx → cooldown; skip filtering when only one deployment), multi-deployment config. Higher-level wrappers add structured output/streaming. Model **tiers** decoupled from concrete model IDs → flag-gated, per-user, staged rollout to new models with zero prod impact until promoted.
  - **Agentic executor = explicit state machine** (`IDLE → PLANNING → EXECUTE_TOOL* → RESPOND → STOPPED`), run async on a job queue, streamed to client via pubsub → GraphQL subscriptions; predefined vs LLM-generated plans; a tool registry, each tool bound to a data source.
  - **RAG:** embeddings → vector DB (cosine, metadata filter) → reranker; hybrid vector+lexical.
  - **Indexing pipeline principles:** **thin events** (publish IDs, never full content), durable event-bus+queue over in-request queuing, idempotency/observability at every step.
  - **Why the gateway takes explicit user context** rather than injecting request-scoped context: making the gateway request-scoped would cascade scope to every consumer — same root cause as A12.
  - Eval/experiment harness with parallel evaluators; rich structured logging of every model resolution.

### A16. Quality assessment (of the source архетype — informs where the risk is)
Strengths: enforced layering, cycle-breaking sub-modules over `forwardRef`, machine-enforced tenant-safety, integration-first testing, living/self-critical docs. Risks (the automatable ones): pattern multiplicity (legacy + two module patterns), `any` tolerated by lint, the legacy-bridge leak, silent system-identity fallback, "not full CQRS" naming inviting over-engineering.

---

## Part B — What reviewers actually enforce (from ~100 PR reviews)

### B1. Process shape
- ~50% of PRs got human review comments; ~7 inline comments per reviewed PR. **Zero formal "changes requested"** — blocking is done socially through comments + an `issue:` prefix.
- **AI review is first-class:** an automated security reviewer commented on ~91% of PRs (severity-graded, CWE-cited); a second bot did correctness/bug review. Humans **triage** the bots (accept valid, push back with reasoned trade-offs), which frees human reviewers to focus on architecture, layering, and business correctness.

### B2. Theme frequency (what humans flag, ranked)
1. Performance / DB queries (repository responsibility, raw vs native, indexes, splitting queries, flag-gating risky queries)
2. GraphQL schema (resolver/type placement, nullability, ordering, descriptions-over-JSDoc, required inputs)
3. Error handling (throw vs silently default `?? ''`, path reachability, message/code accuracy)
3. Docs/comments (stale JSDoc, prefer schema descriptions, dead comments, follow-up TODOs)
5. Type safety (explicit `any`, enum reuse over stringly-typed, precise types)
6. Architecture/layering (**code placement** — is this the right home for this logic/type/query?)
6. Simplification/DRY (unify duplicated normalizers/helpers/mappers)
8. Testing (missing scenarios, no `expect()` outside tests, helpers use public contracts not internals)
9. Security/permissions (tenant isolation of shared caches, sensitive-data exposure, unauthenticated flows)
10. Migrations (backfill vs self-heal-on-write, `NOT VALID` constraints), logging/observability, back-compat.

The dominant cross-theme question is **code placement / layering**.

### B3. Blocking vocabulary (conventional comments)
`issue:` (closest to blocking) · `question:` (most common; often surfaces a latent bug) · `suggestion:` (optional) · `nit:`/`nitpick:` (minor) · `info:` (author self-annotation) · `praise:`. Tone is collaborative and Socratic; scope creep is deferred to follow-ups rather than blocking a shippable PR.

### B4. Distilled rule set to encode (highest-signal, most-repeated)
1. Repositories are data-access only — flag business logic / god methods in a repository.
2. Code placement — right layer (`domain` vs `providers` vs handler) and right module; resolvers never touch repositories; handlers never call handlers.
3. Throw, don't silently default — flag `?? ''`/`?? []` hiding a real error; check path reachability.
4. Type precision — flag explicit `any`, stringly-typed where an enum exists, over-generic types (the linter won't catch these).
5. Client-facing schema descriptions over code comments; deliberate nullability/ordering.
6. Enum-allowlist `orderBy` + escape `LIKE`/`ILIKE` wildcards on every paginated query.
7. No mutable singleton state — tenant safety.
8. Tests: through the entry point, never mock owned code, never insert into DB directly, behavior-named, assert outcomes not calls.
9. DRY across the domain — detect duplicated normalizers/helpers/mappers.
10. Migrations — call out missing backfill and `NOT VALID` trade-offs.
11. Offline-first — sync prefix, `problems` not `errors`, idempotency, server timestamps, no `as` casting (mappers), composite sync index.
12. Sensitive-data redaction in logs; explicit actor on async producers (no accidental privileged-identity fallback).
