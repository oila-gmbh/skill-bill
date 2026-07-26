# SKILL-27 persistence-core

## Status

In Progress

## Sources

- `docs/migrations/SKILL-27-kotlin-runtime-port.md`
- Feature-implement pre-planning briefing for `SKILL-27`

## Acceptance Criteria

1. Port the Phase 2 persistence foundation from `skill_bill/db.py` into `runtime-kotlin/`, covering DB path resolution, SQLite connection/bootstrap, schema creation, and foundational migrations/backfills.
2. Choose a JVM SQLite access approach intentionally and document that decision in the migration note.
3. Add Kotlin parity tests for schema creation and representative persistence primitives before touching higher-level domains.
4. Keep all new Kotlin files under the existing spotless and detekt gates, with `runtime-kotlin` checks passing.

## Non-goals

- Porting CLI behavior.
- Porting MCP behavior.
- Porting review/learnings/workflow business logic beyond the persistence primitives needed to establish the DB contract.

## Consolidated Spec Content

`SKILL-27` is a multi-session Kotlin runtime port. Python remains the runtime source of truth until later phases explicitly move a subsystem to Kotlin with parity coverage.

Phase 1 is already complete in `runtime-kotlin/`:

- standalone JVM-only Gradle module
- local `build-logic/` included build
- JDK 17 toolchain discipline
- version-catalog dependency management
- module-local `spotless` and `detekt` quality gates
- package scaffolding for future subsystem ports
- initial smoke coverage for the Kotlin runtime scaffold

Phase 2 starts with persistence core. The next implementation work should:

1. map `skill_bill/db.py` and the persistence-facing telemetry/session models into Kotlin packages under `runtime-kotlin/`
2. choose the JDBC or SQLite access approach intentionally and document the decision against the existing SQLite contract
3. add parity-oriented Kotlin tests around schema creation and representative session persistence primitives before touching higher-level domains
4. keep all new Kotlin source and Gradle files inside the `spotless` and `detekt` gates from the start so Phase 2 does not backload quality cleanup

Frozen persistence contract from the migration note:

- preserve the SQLite-backed persistence model
- preserve schema semantics
- preserve backfill behavior that existing runtimes depend on
- preserve workflow/session id persistence and visibility

Relevant Python source-of-truth surfaces for this phase:

- `skill_bill/db.py` for DB path resolution, connection lifecycle, schema bootstrap, additive migrations, and backfills
- `skill_bill/constants.py` for DB path constants, workflow prefixes, workflow contract versions, enum domains, and persistence-related environment keys
- `tests/` for current behavior oracles, especially review metrics, workflow state, workflow stats, and telemetry isolation coverage

Do not start review, CLI, or MCP porting before the persistence primitives are in place.
