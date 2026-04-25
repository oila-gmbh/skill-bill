# Gradle Module Split Evaluation

Issue: SKILL-28
Status: First Split Implemented
Date: 2026-04-25

## Decision

Implement the first physical Gradle module split for the boundaries that
already compile cleanly:

- `runtime-core`
- `runtime-cli`
- `runtime-mcp`

Defer the deeper `runtime-contracts`, `runtime-domain`,
`runtime-application`, `runtime-infra-sqlite`, and `runtime-infra-http` split
until the remaining upward dependencies are removed. This keeps Phase 9
behavior-neutral while still making the user-facing adapters real Gradle
boundaries.

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

- `skillbill.contracts.*` still contains mapper functions that import
  `skillbill.application`, `skillbill.learnings`, `skillbill.review`,
  `skillbill.ports`, and `skillbill.telemetry` types. A standalone
  `runtime-contracts` module would either depend upward or require moving those
  mappers to adapter/application modules first.
- `skillbill.review` is partly domain and partly transitional runtime support:
  pure parsing lives beside stats, SQL, workflow telemetry, and compatibility
  facades. A useful `runtime-domain` split needs those compatibility helpers
  moved behind ports first.
- `RuntimeContext` still defaults its requester through
  `infrastructure.http.JdkHttpRequester`. A clean `runtime-core` or
  `runtime-application` module needs this default moved to a composition root.
- `skillbill.telemetry` still has compatibility runtime facades that import
  infrastructure adapters. A clean `runtime-application` to infrastructure
  direction needs those facades retired or moved.

## Proven Boundaries Today

The current package-level tests already enforce the boundaries that are true
and useful now:

- CLI and MCP delegate shared workflows through application services.
- Application services do not import entrypoint frameworks or SQLite
  infrastructure directly.
- Learnings domain code stays free of JDBC and review runtime dependencies.
- Pure review parsing and triage normalization stay persistence-free.
- Telemetry sync orchestration depends on ports rather than concrete DB,
  filesystem, or HTTP APIs.
- Reserved runtime surfaces expose documented `RuntimeSurfaceContract` metadata.
- CLI and MCP are independently compiled adapter modules.

## Deeper Split Readiness Criteria

Revisit the deeper physical split when these conditions are true:

1. `skillbill.contracts.*` contains DTOs and serializers only; mapping from
   application/domain models lives in adapter or application packages.
2. Pure review domain code is separated from review stats, SQL compatibility,
   and telemetry state helpers.
3. `RuntimeContext` no longer imports infrastructure defaults.
4. Telemetry compatibility facades no longer import infrastructure adapters.
5. Shared test fixtures are either module-owned or available through a small
   one-directional test fixture surface.

## Next Increment

Keep `runtime-core` as the integration module for now and continue reducing the
remaining upward dependencies. Split deeper modules in this order:

1. `runtime-contracts`
2. `runtime-application`
3. `runtime-infra-sqlite` and `runtime-infra-http`
4. `runtime-domain`

This keeps each future extraction aligned with boundaries that already compile
cleanly and avoids a large behavior-neutral PR that is only neutral on paper.
