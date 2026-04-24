---
issue_key: SKILL-28
feature_name: runtime-architecture-hardening
feature_size: LARGE
status: In Progress
created: 2026-04-24
depends_on: SKILL-27 Kotlin runtime foundation, SKILL-27 persistence core, SKILL-27 review domain
---

# SKILL-28 - Runtime CLI, DI, and architecture hardening

## Problem

`runtime-kotlin` has crossed the line from scaffold to real runtime. It now
owns CLI behavior, MCP-facing primitives, SQLite-backed review state,
telemetry, review parsing, triage, learnings, and workflow stats.

The current implementation is functional and tested, but the architecture is
still shaped by the staged port:

- entry points still call domain and persistence functions directly in places
- application services return generic `Map<String, Any?>` payloads
- domain logic, payload construction, SQL, filesystem, HTTP, and environment
  access are still mixed across several runtime objects
- many runtime surfaces are singleton `object` APIs instead of injectable
  services with explicit dependencies
- MCP and CLI do not consistently share the same application use cases
- placeholder runtime surfaces exist for future ports but do not yet express
  real contracts

This is acceptable for a port checkpoint, but not for the long-term runtime
that should become the maintainable source of truth for Skill Bill.

## Product stance

The target is a **modular hexagonal runtime**, not an abstract enterprise
rewrite.

For Skill Bill, state-of-the-art architecture means:

- CLI and MCP are thin adapters
- application use cases are the reusable runtime API
- domain logic is independent of Clikt, SQLite, HTTP, filesystem, and process
  environment
- infrastructure is behind explicit ports and adapters
- contracts are typed at internal boundaries and serialized only at external
  boundaries
- Kotlin-Inject wires the graph at composition roots, not deep inside business
  logic
- tests protect behavior, dependency direction, migrations, and output
  contracts

The runtime should remain pragmatic. Do not split modules, invent abstractions,
or generalize frameworks before the current seams are explicit and proven.

## Shipped baseline

SKILL-28 has already established the first architecture baseline:

- Clikt-backed CLI with built-in help, nested command docs, shell completion,
  option validation, and stable command aliases
- runtime-level Kotlin-Inject setup
- initial `skillbill.application` layer between CLI commands and lower-level
  runtimes
- broad CLI regression coverage
- Kotlin, KSP, kotlin-inject, and Clikt versions updated
- shared learning session payload helper moved into the `learnings` domain
  package

This spec records the next architecture steps after that baseline.

## Current runtime shape

`runtime-kotlin` is still a single Gradle module. Current package surfaces:

- `skillbill.cli`
- `skillbill.mcp`
- `skillbill.application`
- `skillbill.di`
- `skillbill.db`
- `skillbill.telemetry`
- `skillbill.review`
- `skillbill.learnings`
- `skillbill.workflow.implement`
- `skillbill.workflow.verify`
- `skillbill.scaffold`
- `skillbill.contracts`
- `skillbill.install`
- `skillbill.launcher`
- `skillbill.error`

The single-module shape should stay until package boundaries are enforceable
and useful. A Gradle module split should be a later extraction, not the first
move.

## Target dependency direction

The intended dependency direction is:

```text
cli / mcp
  -> application use cases
    -> domain services + ports
      -> domain models

infrastructure/sqlite, infrastructure/http, infrastructure/fs
  -> implement domain/application ports

di
  -> wires adapters, use cases, repositories, clients, clocks, and runtime
     contexts
```

Boundary rule:

- `cli` and `mcp` may depend on `application`.
- `application` may depend on domain packages and ports.
- domain packages must not depend on Clikt, JDBC, HTTP clients, filesystem, or
  process environment.
- infrastructure packages may depend on domain and application ports.
- `di` may depend on every implementation package needed to wire the graph.

## Target package shape

The near-term package shape should remain inside one Gradle module:

```text
skillbill/
  cli/                  # Clikt commands, terminal rendering, completion
  mcp/                  # MCP adapter surface only
  application/          # use cases, typed input/output models
  domain/
    review/
    learnings/
    telemetry/
    workflow/
  ports/
    persistence/
    telemetry/
    fs/
    time/
  infrastructure/
    sqlite/
    http/
    fs/
  contracts/            # external JSON/contract DTOs and serializers
  di/                   # Kotlin-Inject components and providers
  error/
```

Existing packages can move incrementally. Do not perform a package-wide move
until tests and boundary checks are in place.

## Architecture principles

### 1. Entry points are adapters

CLI and MCP should validate and translate input, then delegate to application
use cases. They should not own SQL, transaction, telemetry, or domain behavior.

### 2. Application use cases are the reusable runtime API

Both CLI and MCP should call the same application services for:

- importing reviews
- triaging findings
- recording feedback
- resolving and managing learnings
- reading review and workflow stats
- reading and mutating telemetry config
- syncing telemetry
- doctor/version checks

### 3. Domain models are typed

Important concepts should not travel through raw strings and generic maps once
inside the runtime. Introduce typed models for:

- review run ids
- review session ids
- finding ids
- learning ids
- learning status
- learning scope
- feedback outcome
- telemetry level
- workflow names
- workflow status

Keep wire names stable at the boundary.

### 4. Infrastructure is behind ports

The application/domain layers should not directly own:

- `java.sql.Connection`
- `DriverManager`
- `Files`
- `Path.of(System.getProperty("user.home"))`
- `System.getenv()`
- `HttpClient`

Introduce ports first, then move current concrete code behind adapters.

### 5. Transactions belong to use cases

Transaction boundaries should be explicit at the application level through a
`UnitOfWork` or equivalent database session abstraction. Repositories and
domain services should not randomly flip `connection.autoCommit`.

### 6. Payloads are boundary concerns

Domain and application use cases should return typed results. JSON maps and
text rendering belong in:

- CLI presenters
- MCP/contract DTO mappers
- telemetry contract serializers

Generic `Map<String, Any?>` should be kept only at external JSON boundaries and
temporary compatibility layers.

### 7. DI is for composition, not service location

Kotlin-Inject should construct:

- application use cases
- repositories
- HTTP clients
- config providers
- file readers/writers
- clocks
- entry-point components

Avoid injecting one giant runtime context into everything long term. Prefer
injecting smaller dependencies such as `Environment`, `UserHome`, `Clock`,
`DatabaseSessionFactory`, and `TelemetrySettingsProvider`.

## Non-goals

- Redesigning CLI command names, option names, JSON output, MCP payloads, or DB
  schema semantics as part of architecture cleanup.
- Replacing SQLite.
- Introducing coroutine infrastructure unless a real async boundary appears.
- Splitting Gradle modules before package boundaries are stable.
- Creating a framework-heavy architecture that is harder to understand than
  the current runtime.
- Deleting the Python runtime or changing the wider SKILL-27 migration plan.

## Implementation plan

### Phase 0 - Guard the architecture

Add a short runtime architecture document and package boundary tests.

Acceptance criteria:

1. A runtime architecture doc describes package ownership and dependency
   direction.
2. Tests fail when forbidden dependencies appear, at minimum:
   - domain packages depending on `cli`
   - domain packages depending on `mcp`
   - domain packages depending on JDBC or HTTP client APIs
   - CLI bypassing application services for runtime workflows
3. `./gradlew check --no-configuration-cache` passes.

### Phase 1 - Make MCP use application services

MCP currently duplicates workflow orchestration that should belong to
application use cases.

Acceptance criteria:

1. Introduce `McpComponent` or equivalent Kotlin-Inject composition root.
2. Route MCP review, triage, learning, telemetry, stats, doctor, and version
   calls through application services where behavior overlaps CLI.
3. Keep MCP-specific orchestration metadata as an MCP adapter concern.
4. Preserve existing MCP payload tests.
5. Add at least one regression proving CLI and MCP use the same use case for a
   shared workflow.

### Phase 2 - Type application results

Replace application-layer `Map<String, Any?>` returns with typed input and
output models.

Start with learnings because the surface is bounded and currently shared by
CLI and MCP.

Acceptance criteria:

1. `LearningService` returns typed results for list, show, resolve, add, edit,
   status changes, and delete.
2. CLI/MCP convert typed results to wire payloads at their boundaries.
3. Existing JSON/text output contracts remain stable.
4. Follow with review and telemetry result models in separate PRs.

### Phase 3 - Introduce repository and unit-of-work ports

Move SQL access behind interfaces before moving packages aggressively.

Candidate ports:

- `DatabaseSessionFactory`
- `UnitOfWork`
- `ReviewRepository`
- `LearningRepository`
- `TelemetryOutboxRepository`
- `WorkflowStateRepository`

Acceptance criteria:

1. Application services no longer open databases directly.
2. Transaction ownership is explicit in application use cases.
3. Store classes become SQLite adapters or are replaced by repository
   implementations.
4. Existing DB tests remain as adapter tests.
5. Application tests can use fake repositories for use-case behavior.

### Phase 4 - Separate domain from persistence

Move pure business logic and models away from JDBC-shaped runtime objects.

Acceptance criteria:

1. `LearningRecord` is owned by the learnings domain, not review.
2. Review parsing and triage normalization do not depend on persistence.
3. Learning source validation is expressed as domain rules using repository
   queries rather than static calls across runtime objects.
4. Domain packages do not import `java.sql.Connection`.

### Phase 5 - Make telemetry a ported subsystem

Telemetry currently mixes config file IO, environment overrides, HTTP, DB
outbox, and payload shaping.

Acceptance criteria:

1. Introduce `TelemetrySettingsProvider`, `TelemetryConfigStore`,
   `TelemetryClient`, and `TelemetryOutboxRepository`.
2. Keep HTTP request/response details in an HTTP adapter.
3. Keep config file reads/writes in a filesystem adapter.
4. Keep telemetry contract DTOs separate from sync orchestration.
5. Sync behavior remains covered for disabled, noop, synced, failed, and
   unconfigured paths.

### Phase 6 - Version DB migrations explicitly

The current migration model is idempotent but not versioned as a first-class
history.

Acceptance criteria:

1. Add a `schema_migrations` table.
2. Represent migrations as named/versioned migration objects.
3. Preserve current additive/backfill behavior.
4. Keep compatibility tests for legacy DB shapes.
5. New migrations are append-only and deterministic.

### Phase 7 - Define contract DTOs and presenters

Separate internal result models from external JSON/text contracts.

Acceptance criteria:

1. CLI text rendering depends on typed presenter models, not raw maps.
2. JSON output is produced through explicit DTO/serializer mappers.
3. MCP payloads have explicit DTOs where they differ from CLI payloads.
4. Golden tests cover representative CLI and MCP JSON payloads.

### Phase 8 - Implement or retire placeholder surfaces

The placeholder marker interfaces should either become real ports or move out
of the runtime surface.

Acceptance criteria:

1. `install`, `launcher`, `scaffold`, `workflow.implement`, and
   `workflow.verify` each have either a real runtime contract or an explicit
   documented reason to remain placeholder-only.
2. Runtime smoke tests assert meaningful contracts, not just package presence.

### Phase 9 - Optional Gradle module split

Only after phases 0-8, evaluate whether physical modules are worth the cost.

Candidate modules:

- `runtime-contracts`
- `runtime-domain`
- `runtime-application`
- `runtime-infra-sqlite`
- `runtime-infra-http`
- `runtime-cli`
- `runtime-mcp`

Acceptance criteria:

1. Module split enforces boundaries already proven by tests.
2. Build time and developer ergonomics remain acceptable.
3. No behavior changes are bundled with the split.

## Validation strategy

Every phase should run:

```bash
(cd runtime-kotlin && ./gradlew check --no-configuration-cache)
```

Architecture-specific phases should also add or update:

- package boundary tests
- CLI contract tests
- MCP contract tests
- persistence adapter tests
- telemetry adapter tests
- golden output fixtures for stable JSON surfaces

## Suggested PR sequence

1. Architecture doc and boundary tests.
2. MCP routes through application services.
3. Typed learning application results.
4. Typed review application results.
5. Typed telemetry application results.
6. Repository ports and SQLite adapters.
7. Unit-of-work transaction cleanup.
8. Telemetry ports and HTTP/filesystem adapters.
9. Versioned DB migrations.
10. Contract DTOs and golden payload tests.
11. Placeholder runtime surfaces cleanup.
12. Optional Gradle module split.

## Open questions

1. Should `application` remain the public Kotlin runtime API package, or should
   it eventually be renamed to `usecases` after contracts settle?
   Recommendation: keep `application`; it is clear enough and aligns with the
   target dependency direction.

2. Should typed results be serializable DTOs or internal domain result models?
   Recommendation: internal result models first, explicit DTO mappers at the
   CLI/MCP/contract boundary.

3. Should the DB layer adopt a SQL library?
   Recommendation: keep JDBC for now. Introduce repository ports first. Revisit
   a query library only if SQL volume or migration complexity justifies it.

4. Should Kotlin-Inject replace all singleton runtime objects immediately?
   Recommendation: no. Replace them only as dependencies become explicit.

