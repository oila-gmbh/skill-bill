---
name: bill-go-code-review-architecture
description: Use when reviewing Go architecture, module boundaries, dependency direction, source-of-truth ownership, and framework coupling.
internal-for: bill-code-review
---

# Architecture Review Specialist

Review only high-signal architectural issues.

## Focus

- Layer boundaries, dependency direction, and framework or persistence coupling
- Module, bounded-context, and source-of-truth ownership
- Application/use-case orchestration, transaction ownership, and boundary translation
- Sync/async boundaries, events, outbox flows, projections, and cross-boundary composition
- Dependency-injection/container lifetime and lifecycle-safe wiring

## Ignore

- Formatting, style, and framework-idiom differences without architectural impact
- Naming, terminology, or pattern preferences without concrete risk
- Calls for DDD, hexagonal, onion, CQRS, or modular-monolith purity when local architecture intentionally uses simpler CRUD or MVC and the changed behavior remains simple

## Applicability

Use this specialist across Go backend, service, worker, CLI, and server-rendered entry points when changed code can affect ownership, boundaries, orchestration, lifecycle, or architectural consistency. First infer the architecture established in the changed area and adjacent modules; treat coherent local architecture and project guidance as the contract for consistency. Where the local architecture is absent, weak, inconsistent, or accidental, guide changes toward established Go/backend architecture practices through concrete risk-based findings, not terminology preference.

This reviewer controls what qualifies as an architecture issue. It must still use the shared review output contract; do not introduce custom sections, severities, or finding formats.

Apply shared architecture rules to every review. Apply deeper concern-specific checks only when the changed code touches those areas.

## Project-Specific Rules

### Architectural Precedence

- Honor coherent local architecture and project-specific standards before applying pack defaults
- Identify the project's established architecture in the changed area and adjacent modules before reporting architecture findings
- If the project uses DDD, layered services, hexagonal/onion, modular monolith, CQRS, conventional MVC, or another explicit style, preserve that style consistently across the module, bounded context, or feature area
- Do not mix architectural styles within the same module, bounded context, or feature area unless the project already defines that split
- Do not preserve local patterns that are inconsistent, accidental, or harmful merely because they already exist
- Respect simple CRUD or conventional MVC shapes for simple behavior, but flag changes that add business rules, cross-module coordination, state transitions, side effects, async/retry behavior, or persistence complexity without suitable boundaries
- When no coherent local architecture is established, guide the code toward established Go/backend practices: thin entry points, explicit application/service boundaries, clear transaction ownership, validated boundaries, consistent persistence ownership, explicit dependencies, and business rules kept out of transport/rendering details when complexity warrants it
- Report architecture issues only when the change creates or worsens concrete risk: maintainability loss, duplicated business rules, unclear ownership, testability problems, transaction ambiguity, security gaps, data consistency risk, scalability limits, or runtime correctness risk
- Architecture findings should name the misplaced responsibility, the boundary or owner that should own it, and the concrete consequence that makes the issue worth reporting

### Vocabulary And Pattern Fit

- Use the project's existing architectural vocabulary when it is coherent with the project's architecture and accurately describes the role the code plays
- Prefer common local terms such as repository, query service, read model, projection, module API, application service, client, adapter, or port according to the project's vocabulary and the actual boundary being modeled
- Do not introduce a different term or pattern just because it is personally preferred
- Do not preserve local terminology when it hides an architectural violation; if a class is called a repository but performs cross-module orchestration, remote API coordination, authorization decisions, or multi-step business workflows, review the architectural role rather than the name
- Report naming or pattern-fit issues only when the mismatch creates concrete risk: unclear ownership, boundary leakage, duplicated business logic, transaction ambiguity, testability loss, coupling, security gaps, or consistency problems

### Shared Architecture

- Keep business workflows and invariants independent from transport, rendering, and storage details when complexity warrants that separation or the project architecture already requires it
- Dependencies should preserve the project's intended direction; stable business rules should not become unnecessarily coupled to volatile frameworks or concrete infrastructure details
- Preserve a single source of truth for each important piece of business state; avoid duplicated ownership across layers or modules
- Keep API/transport DTOs, domain objects, persistence models or ORM records, form/view models, and queue/message payloads separate when their lifecycle, ownership, trust boundary, or shape meaningfully differs
- Do not leak framework-specific models or persistence-shaped data across unrelated boundaries when that creates tight coupling
- External systems should sit behind explicit clients, adapters, ports, repositories, or other project-standard boundary interfaces that match the project's vocabulary and the actual role being modeled
- Prefer explicit dependencies and visible wiring over service locators, hidden globals, or framework magic that obscures ownership
- Service/container lifetimes should match object lifetime; long-lived services must not quietly own request, tenant, user, transaction, or job-specific state
- CLI commands, queue workers, scheduled jobs, and event listeners should reuse the same application/use-case boundaries as synchronous entry points instead of duplicating business workflows

### Architectural Pattern Checks

- When the project uses modular monolith, bounded contexts, or strong module ownership, each module should own its own data and writes; cross-module writes are architectural smells unless the project explicitly chooses a simpler structure
- When the project uses DDD, layered, hexagonal, onion, or clean-architecture boundaries, domain should not depend on infrastructure or transport details
- Application/use-case/service code should orchestrate behavior when workflows or invariants are non-trivial; do not hide business workflows inside controllers, request objects, ORM models, templates, or infrastructure listeners when that contradicts the local architecture
- One business operation should have one clear use-case owner and one clear transaction owner unless the project explicitly models partial success or eventual consistency
- When the project uses CQRS-style read boundaries, read paths should use explicit query services, read models, projections, or other declared read-side APIs rather than leaking persistence access into higher layers
- Cross-module or cross-boundary composition should happen through declared module APIs, application services, or dedicated coordinators, not by reaching directly into another boundary's internals
- When the project uses ports/adapters, consumers should define thin, explicit contracts and providers should implement them without pulling consumer orchestration logic back into the provider

### Data Ownership / Read Composition / Persistence Boundaries

- Repository, query service, read model, projection, and module API boundaries must not leak query-builder details, ORM sessions, transaction handles, or persistence records into higher layers unless that is an explicit architectural choice
- Direct cross-boundary reads, cross-module joins, and cross-module ORM leakage are high-severity smells when the architecture expects module APIs, ports, projections, read models, or query services
- Cross-boundary enrichment should happen in higher-level application services, declared module APIs, or dedicated coordinators, not inside low-level repositories or query helpers
- Hot or repeated cross-boundary reads should prefer explicit projections, read models, caches, query services, or batched module/API calls over convenience coupling
- Query/read boundaries should stay single-purpose and single-source; low-level read abstractions should not quietly become multi-source orchestration hubs

### Events / Outbox / Integration Architecture

- Domain events, integration events, and projections should have distinct roles; do not blur cross-module messaging with in-module business logic
- Cross-module or replayable reactions should use explicit integration-event or messaging boundaries rather than hidden synchronous coupling
- When the project uses CQRS without event sourcing, keep write-side business logic separate from read-side projections and derived views
- Reliable publish-after-commit flows should keep event persistence and business state changes in the same transaction when the architecture expects an outbox pattern
- Projectors and read-model updaters should update derived state only, safely and atomically; they must not contain business workflows or rely on read-modify-write convenience flows
- Event handler type should match intent: business-operation listeners trigger use cases, while projectors update derived state only
- Event-triggered business work should converge on the same application/use-case boundaries instead of duplicating business logic in multiple places

### Transport / Entry-Point Orchestration

- Controllers, routes, RPC handlers, actions, and server-rendered entry points should stay thin: derive context, validate input, call a use case, and map the response
- Entry points should not become hidden composition roots for cross-module reads, transaction management, or business workflows when the architecture expects dedicated application boundaries
