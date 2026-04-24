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
- `skillbill.db`: SQLite schema, migrations, connection bootstrap, and current
  JDBC stores.
- `skillbill.review`: review parsing, triage, review metrics, review telemetry
  state, and current review persistence helpers.
- `skillbill.learnings`: learning scope rules, learning store behavior, and
  learning payload helpers.
- `skillbill.telemetry`: telemetry config, sync orchestration, HTTP contracts,
  and current telemetry adapter code.
- `skillbill.contracts`: shared JSON and runtime contract helpers.
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
- future `skillbill.domain.*` packages are protected from infrastructure
  imports as soon as they appear

## Near-Term Refactor Order

1. Continue replacing non-learning application-layer `Map<String, Any?>`
   results with typed results.
2. Introduce repository and unit-of-work ports.
3. Move pure domain models/rules away from JDBC-shaped runtime objects.
4. Put telemetry config, HTTP, and filesystem behavior behind explicit ports.
5. Add versioned database migrations.
6. Add contract DTOs and golden output fixtures.
7. Split Gradle modules only after package boundaries are proven.
