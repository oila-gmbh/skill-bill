# Skill Bill Runtime Architecture

This document defines the intended architecture for `runtime-kotlin` while the
runtime is still moving from a staged port to the long-term source of truth.

The runtime should be a pragmatic hexagonal JVM runtime:

```text
cli / mcp
  -> application use cases
    -> ports
      -> domain models

infrastructure/sqlite, infrastructure/http, infrastructure/fs
  -> implement ports

di
  -> wires adapters, use cases, repositories, clients, clocks, and runtime
     contexts
```

## Gradle Modules

- `runtime-contracts`: JSON helpers, contract DTOs, runtime surface contracts,
  and runtime exception types.
- `runtime-domain`: pure learning, review, telemetry, and version models/rules;
  public domain data types live in area-owned `model` packages.
- `runtime-ports`: shared `skillbill.model.RuntimeContext`, persistence and
  telemetry port interfaces, and port-owned model types.
- `runtime-application`: reusable CLI/MCP use cases and port-backed telemetry
  orchestration; public use-case inputs/results live in
  `skillbill.application.model`.
- `runtime-infra-sqlite`: SQLite schema, migrations, stores, repositories, and
  SQL-backed review helpers.
- `runtime-infra-http`: telemetry HTTP client/requester and HTTP wire mapping.
- `runtime-infra-fs`: telemetry config file adapter.
- `runtime-core`: Kotlin-Inject composition root, module metadata, active
  workflow surface declarations, and remaining reserved runtime surfaces.
- `runtime-cli`: Clikt command tree, terminal rendering, JSON output, help, and
  completion surfaces.
- `runtime-mcp`: MCP adapter surface and MCP-specific payload shaping.

## Current Package Ownership

- `skillbill.cli`: Clikt command tree, option validation, shell completion,
  terminal text rendering, and CLI JSON output.
- `skillbill.mcp`: MCP-facing adapter surface. It delegates overlapping
  workflows to `skillbill.application` and keeps MCP-specific orchestration
  metadata at the adapter boundary.
- `skillbill.application`: reusable runtime use cases for CLI, MCP, and future
  entry points. Public use-case inputs and results live in
  `skillbill.application.model`.
- `skillbill.model`: shared runtime model types that are not owned by a narrower
  domain area, currently `RuntimeContext`.
- `skillbill.di`: Kotlin-Inject composition roots and providers.
- `skillbill.ports`: internal port contracts for persistence sessions,
  repositories, telemetry settings/config/client abstractions, and later
  filesystem/time abstractions. Port DTOs/results live under
  `skillbill.ports.*.model`.
- `skillbill.infrastructure`: concrete adapters for port contracts. SQLite
  adapters own JDBC connection/session behavior and table-shaped repository
  implementations, including SQL-backed review persistence, stats, and review
  telemetry state; HTTP adapters own telemetry relay request/response details;
  filesystem adapters own telemetry config file reads and writes.
- `skillbill.db`: SQLite schema, migrations, connection bootstrap, and current
  JDBC stores.
- `skillbill.review`: pure review parsing, triage decision normalization, and
  input reading helpers. Review data types live in `skillbill.review.model`.
  It must stay free of JDBC, SQLite infrastructure, telemetry facades, and
  persistence adapters.
- `skillbill.learnings`: learning scope/source validation rules and learning
  payload helpers. Learning data types live in `skillbill.learnings.model`.
  It must stay free of JDBC.
- `skillbill.telemetry`: telemetry settings normalization, sync orchestration,
  config mutation rules, and port-backed compatibility facades. Telemetry data
  types live in `skillbill.telemetry.model`.
- `skillbill.contracts`: shared JSON and runtime contract DTOs plus pure
  serialization helpers. Mapping from application/domain/port models into
  contract DTOs belongs in application or adapter-owned packages.
- `skillbill.error`: runtime exception taxonomy.
- `skillbill.workflow.*`: active durable workflow state/runtime surfaces for
  feature-implement and feature-verify. State behavior is owned by
  `skillbill.application.WorkflowService`, modeled in `runtime-domain`, mapped
  through `runtime-contracts`, and persisted through workflow repository ports
  backed by the existing SQLite tables.
- `skillbill.install`: active Kotlin-owned install primitives for agent-path
  detection, skill symlink installation, and install rollback support. The
  shell installer remains Python-owned until cutover packaging changes
  intentionally move it.
- `skillbill.scaffold`: active Kotlin-owned governed loader and scaffold
  mutation surface, including manifest discovery, scaffold planning, symlink
  wiring, and rollback primitives.
- `skillbill.launcher`: active runtime-selection surface. The installed
  `skill-bill` script defaults to the Kotlin CLI through the launcher and
  supports `SKILL_BILL_RUNTIME=python` as the rollback path. `skill-bill-mcp`
  defaults to the Kotlin stdio server and supports
  `SKILL_BILL_MCP_RUNTIME=python` as the rollback path. MCP telemetry
  lifecycle tools are Kotlin-native.

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
7. Public data/enum/sealed model declarations in application, domain, and port
   modules belong in explicit `model` packages; services, runtimes, and port
   interfaces import those models instead of declaring them inline.
8. Application use cases must access SQLite through repository and unit-of-work
   ports. Read use cases call a read session; write use cases call an explicit
   transaction session.
9. Telemetry application use cases must depend on `TelemetrySettingsProvider`,
   `TelemetryConfigStore`, `TelemetryClient`, and `TelemetryOutboxRepository`.
   HTTP request mechanics belong in `infrastructure/http`; config file IO
   belongs in `infrastructure/fs`; telemetry proxy DTOs belong in `contracts`
   and telemetry proxy payload mapping belongs with the HTTP adapter.

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
- public application/domain/port model declarations must live under explicit
  `model` packages, e.g. `skillbill.learnings.model` or
  `skillbill.ports.persistence.model`
- review parsing and triage decision normalization are pure surfaces that do
  not import JDBC or persistence adapters
- SQL-backed review persistence, stats, workflow telemetry, and feedback
  helpers live under `skillbill.infrastructure.sqlite.review`
- workflow state persistence reuses `feature_implement_workflows` and
  `feature_verify_workflows` through `WorkflowStateRepository`; there is no
  workflow-local store or schema redesign
- telemetry sync orchestration depends on telemetry ports and the outbox
  repository, not on SQLite, filesystem IO, or Java HTTP APIs
- telemetry HTTP request/response details live under `skillbill.infrastructure.http`
  and telemetry config file IO lives under `skillbill.infrastructure.fs`
- telemetry proxy batch and stats wire payloads are explicit contract helpers,
  separate from sync orchestration; model-to-contract mapping must stay out of
  `skillbill.contracts`
- SQLite schema changes are represented as append-only versioned database migrations
  and recorded in `schema_migrations`
- CLI and MCP JSON output should be produced through explicit contract DTOs and
  mappers, while CLI text rendering should consume typed CLI presenter models
  instead of raw maps
- runtime surfaces must expose a documented `RuntimeSurfaceContract`; active
  workflow surfaces declare open/update/get/list/latest/resume/continue,
  scaffold/install declare their supported operations, and the launcher
  declares the Python/Kotlin selection and fallback operations
- `RuntimeContext` lives in `skillbill.model` and must not import
  infrastructure defaults; concrete adapters are provided by CLI/MCP contexts
  or DI composition roots
- the physical Gradle module split includes `runtime-contracts`,
  `runtime-domain`, `runtime-ports`, `runtime-application`,
  `runtime-infra-sqlite`, `runtime-infra-http`, `runtime-infra-fs`,
  `runtime-core`, `runtime-cli`, and `runtime-mcp`
- `docs/architecture/gradle-module-split-evaluation.md` records the physical
  split decision and the readiness rules that must remain true
- future `skillbill.domain.*` packages are protected from infrastructure
  imports as soon as they appear

## Near-Term Refactor Order

1. Prepare Phase 9 cutover by keeping
   `docs/migrations/SKILL-27-cutover-checklist.md` current with the default
   runtime, Kotlin opt-in path, validation gates, and rollback path.
2. Continue replacing non-learning application-layer `Map<String, Any?>`
   results with typed results.
3. Add contract DTOs and golden output fixtures for the remaining JSON
   surfaces.
4. Continue tightening public APIs so each physical module exposes only the
   contracts needed by downstream modules.
