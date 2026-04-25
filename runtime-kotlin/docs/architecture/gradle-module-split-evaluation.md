# Gradle Module Split Evaluation

Issue: SKILL-28
Status: First Split Implemented; Deeper Split Blockers Cleaned
Date: 2026-04-25

## Decision

Implement the first physical Gradle module split for the boundaries that
already compile cleanly:

- `runtime-core`
- `runtime-cli`
- `runtime-mcp`

Defer the deeper `runtime-contracts`, `runtime-domain`,
`runtime-application`, `runtime-infra-sqlite`, and `runtime-infra-http` split
to a dedicated extraction. The package-level upward dependencies that blocked
that work have been removed, but the physical split should still land
separately to keep the already-open module PR reviewable.

## Current Modules

- `runtime-core`: application services, domain packages, ports, infrastructure
  adapters, database runtime, telemetry runtime, DI, contracts, and reserved
  runtime surfaces.
- `runtime-cli`: Clikt command tree, CLI option validation, terminal
  presenters, JSON output, help/completion surfaces, and CLI tests.
- `runtime-mcp`: MCP adapter surface, MCP payload shaping, MCP tests, and the
  runtime smoke test that verifies cross-module declarations.

`runtime-cli` and `runtime-mcp` expose `runtime-core` as an API dependency
because their public context models expose core runtime port types such as
`RuntimeContext` and telemetry request abstractions.

## Candidate Modules Evaluated

- `runtime-contracts`
- `runtime-domain`
- `runtime-application`
- `runtime-infra-sqlite`
- `runtime-infra-http`
- `runtime-cli`
- `runtime-mcp`

## Deeper Split Blockers

No known package-level upward dependencies remain for the evaluated deeper
split. The remaining work is mechanical Gradle extraction, dependency
declaration, and test-fixture ownership.

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

## Deeper Split Readiness Criteria

The deeper physical split is ready to plan when these conditions remain true:

1. `skillbill.contracts.*` contains DTOs and serializers only; mapping from
   application/domain models lives in adapter or application packages.
2. Pure review domain code is separated from review stats, SQL compatibility,
   and telemetry state helpers.
3. `RuntimeContext` no longer imports infrastructure defaults.
4. Telemetry compatibility facades no longer import infrastructure adapters.
5. Shared test fixtures are either module-owned or available through a small
   one-directional test fixture surface.

## Next Increment

Keep `runtime-core` as the integration module for the current PR. The next
architecture phase should extract the deeper modules in this order:

1. `runtime-contracts`
2. `runtime-application`
3. `runtime-infra-sqlite` and `runtime-infra-http`
4. `runtime-domain`

This keeps each future extraction aligned with boundaries that already compile
cleanly and avoids a large behavior-neutral PR that is only neutral on paper.
