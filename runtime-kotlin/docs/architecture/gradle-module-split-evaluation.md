# Gradle Module Split Evaluation

Issue: SKILL-28
Status: Deeper Split Implemented
Date: 2026-04-25

## Decision

Implement the physical Gradle split for the boundaries that now compile
cleanly:

- `runtime-contracts`
- `runtime-domain`
- `runtime-ports`
- `runtime-application`
- `runtime-infra-sqlite`
- `runtime-infra-http`
- `runtime-infra-fs`
- `runtime-core`
- `runtime-cli`
- `runtime-mcp`

`runtime-core` is now the integration/composition module instead of the owner
of all runtime implementation code.

## Current Modules

- `runtime-contracts`: JSON helpers, runtime contract DTOs, runtime surface
  contracts, and runtime exception types.
- `runtime-domain`: learning, review, telemetry, and version domain models and
  pure rules.
- `runtime-ports`: `RuntimeContext`, persistence ports, and telemetry ports.
- `runtime-application`: CLI/MCP-shared use cases, contract mappers, and
  port-backed telemetry orchestration.
- `runtime-infra-sqlite`: SQLite schema/migrations/stores/repositories and
  SQL-backed review helpers.
- `runtime-infra-http`: telemetry HTTP requester/client and proxy wire mapping.
- `runtime-infra-fs`: telemetry config file adapter.
- `runtime-core`: Kotlin-Inject runtime composition root, module metadata, and
  reserved runtime surfaces.
- `runtime-cli`: Clikt command tree, CLI option validation, terminal
  presenters, JSON output, help/completion surfaces, and CLI tests.
- `runtime-mcp`: MCP adapter surface, MCP payload shaping, MCP tests, and the
  runtime smoke test that verifies cross-module declarations.

`runtime-cli` and `runtime-mcp` expose `runtime-core` as an API dependency
because `runtime-core` re-exports the shared application, domain, port,
contract, and infrastructure modules required by their public context and
composition surfaces.

## Candidate Modules Evaluated

- `runtime-contracts`
- `runtime-domain`
- `runtime-ports`
- `runtime-application`
- `runtime-infra-sqlite`
- `runtime-infra-http`
- `runtime-infra-fs`
- `runtime-cli`
- `runtime-mcp`

## Deeper Split Blockers

No known package-level upward dependencies remain for the implemented split.

## Resolved Split Blockers

- `skillbill.contracts.*` now contains DTOs, serializer helpers, and pure
  contract defaults only. Mapping from application/domain/port models into
  contract DTOs lives in application or adapter-owned packages.
- `RuntimeContext` no longer imports the HTTP infrastructure adapter. CLI and
  MCP composition contexts provide the JDK requester, while core defaults to an
  explicit unconfigured requester for contexts that do not use HTTP.
- SQL-backed review persistence, stats, feedback, and workflow telemetry
  helpers moved under `skillbill.infrastructure.sqlite.review`. The
  `skillbill.review` package now contains review models, parsing, triage
  normalization, and input helpers without JDBC or infrastructure imports.
- Telemetry compatibility facades now depend on telemetry/config/client ports
  rather than constructing filesystem, SQLite, or HTTP infrastructure adapters.

## Proven Boundaries Today

The current package-level tests already enforce the boundaries that are true
and useful now:

- CLI and MCP delegate shared workflows through application services.
- Application services do not import entrypoint frameworks or SQLite
  infrastructure directly.
- Learnings domain code stays free of JDBC and review runtime dependencies.
- The review package stays persistence-free; SQL-backed review helpers live
  under SQLite infrastructure.
- Telemetry sync orchestration depends on ports rather than concrete DB,
  filesystem, or HTTP APIs.
- Telemetry compatibility facades depend on ports rather than infrastructure
  adapters.
- Reserved runtime surfaces expose documented `RuntimeSurfaceContract` metadata.
- CLI and MCP are independently compiled adapter modules.
- Contract, domain, port, application, SQLite, HTTP, and filesystem runtime
  layers compile as independent Gradle modules.

## Deeper Split Readiness Criteria

The deeper physical split is expected to preserve these conditions:

1. `skillbill.contracts.*` contains DTOs and serializers only; mapping from
   application/domain models lives in adapter or application packages.
2. Pure review domain code is separated from review stats, SQL compatibility,
   and telemetry state helpers.
3. `RuntimeContext` no longer imports infrastructure defaults.
4. Telemetry compatibility facades no longer import infrastructure adapters.
5. Shared test fixtures remain module-owned or available only through a small
   one-directional test fixture surface.

## Next Increment

Keep tightening public APIs so each module exposes only the minimum downstream
surface. The next cleanup candidates are moving module-specific tests closer to
their owning modules and reducing `runtime-core` API re-exports once CLI/MCP
declare their direct dependencies explicitly.
