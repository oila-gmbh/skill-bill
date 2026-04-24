# Skill Bill Runtime Architecture

This document defines the intended architecture for `runtime-kotlin` while the
runtime is still moving from a staged port to the long-term source of truth.

The runtime should be a pragmatic hexagonal JVM runtime:

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

## Current Package Ownership

- `skillbill.cli`: Clikt command tree, option validation, shell completion,
  terminal text rendering, and CLI JSON output.
- `skillbill.mcp`: MCP-facing adapter surface. It delegates overlapping
  workflows to `skillbill.application` and keeps MCP-specific orchestration
  metadata at the adapter boundary.
- `skillbill.application`: reusable runtime use cases for CLI, MCP, and future
  entry points.
- `skillbill.di`: Kotlin-Inject composition roots and providers.
- `skillbill.ports`: internal port contracts for persistence sessions,
  repositories, telemetry settings/config/client abstractions, and later
  filesystem/time abstractions.
- `skillbill.infrastructure`: concrete adapters for port contracts. SQLite
  adapters own JDBC connection/session behavior and table-shaped repository
  implementations; HTTP adapters own telemetry relay request/response details;
  filesystem adapters own telemetry config file reads and writes.
- `skillbill.db`: SQLite schema, migrations, connection bootstrap, and current
  JDBC stores.
- `skillbill.review`: pure review parsing and triage decision normalization
  plus transitional review metrics, review telemetry state, and current review
  persistence helpers.
- `skillbill.learnings`: learning records, learning scope rules, learning
  source validation rules, and learning payload helpers. It must stay free of
  JDBC.
- `skillbill.telemetry`: telemetry settings normalization, sync orchestration,
  config mutation rules, sync result models, and compatibility facades.
- `skillbill.contracts`: shared JSON and runtime contract helpers, including
  telemetry proxy DTO/payload mappers.
- `skillbill.error`: runtime exception taxonomy.
- `skillbill.workflow.*`, `skillbill.install`, `skillbill.launcher`, and
  `skillbill.scaffold`: placeholder or early runtime surfaces for later
  SKILL-27/SKILL-28 phases.

## Boundary Rules

These are the stable dependency rules the runtime should converge toward.

1. `cli` and `mcp` are adapters. They validate and translate input, then
   delegate to application use cases.
2. `application` owns workflow orchestration. It must not depend on Clikt or
   MCP types.
3. Domain packages must not depend on CLI, MCP, JDBC, HTTP clients,
   filesystem, or process environment APIs.
4. Infrastructure packages may depend on domain/application ports and concrete
   JVM APIs.
5. `di` is the composition layer. It may depend on implementation packages
   required to wire the graph.
6. JSON maps and terminal strings are boundary concerns. Internal use cases
   should move toward typed input and output models.
7. Application use cases must access SQLite through repository and unit-of-work
   ports. Read use cases call a read session; write use cases call an explicit
   transaction session.
8. Telemetry application use cases must depend on `TelemetrySettingsProvider`,
   `TelemetryConfigStore`, `TelemetryClient`, and `TelemetryOutboxRepository`.
   HTTP request mechanics belong in `infrastructure/http`; config file IO
   belongs in `infrastructure/fs`; telemetry proxy DTOs belong in `contracts`.

## Architecture Guardrails

The current test guardrails enforce the boundaries that are true today and
useful for the next refactors:

- the architecture document must exist and name the package ownership rules
- `RuntimeModule` must declare the current top-level runtime package surfaces
- application services must remain independent from entry-point frameworks
- CLI workflow commands must use application services rather than reaching
  directly into DB, review, telemetry, or learning stores
- MCP workflow calls must use application services rather than reaching
  directly into DB, review, telemetry runtime implementations, or learning
  stores
- learning application use cases return typed results; CLI and MCP map those
  results to JSON-compatible payloads at their adapter boundaries
- application services use persistence ports rather than opening SQLite
  databases, importing JDBC, or checking database files directly
- repository and unit-of-work ports are the required application persistence
  boundary
- LearningRecord is owned by the learnings domain, while SQLite table access
  for learnings lives in infrastructure adapters
- review parsing and triage decision normalization are pure surfaces that do
  not import JDBC or persistence adapters
- telemetry sync orchestration depends on telemetry ports and the outbox
  repository, not on SQLite, filesystem IO, or Java HTTP APIs
- telemetry HTTP request/response details live under `skillbill.infrastructure.http`
  and telemetry config file IO lives under `skillbill.infrastructure.fs`
- telemetry proxy batch and stats wire payloads are explicit contract helpers,
  separate from sync orchestration
- SQLite schema changes are represented as append-only versioned database migrations
  and recorded in `schema_migrations`
- CLI and MCP JSON output should be produced through explicit contract DTOs and
  mappers, while CLI text rendering should consume typed CLI presenter models
  instead of raw maps
- future `skillbill.domain.*` packages are protected from infrastructure
  imports as soon as they appear

## Near-Term Refactor Order

1. Continue replacing non-learning application-layer `Map<String, Any?>`
   results with typed results.
2. Move remaining pure domain models/rules away from JDBC-shaped runtime
   objects.
3. Add contract DTOs and golden output fixtures for the remaining JSON
   surfaces.
4. Split Gradle modules only after package boundaries are proven.
