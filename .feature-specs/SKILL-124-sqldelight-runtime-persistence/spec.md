---
status: Pending
---

# SKILL-124 - SQLDelight runtime persistence migration

Created: 2026-07-13
Issue key: SKILL-124
Mode: decomposed

## Intended Outcome

Replace handwritten JDBC statement binding and `ResultSet` decoding in
`runtime-infra-sqlite` with SQLDelight-generated, type-safe Kotlin persistence
APIs while preserving the existing SQLite database, synchronous application
ports, durable workflow semantics, cross-process behavior, and loud-fail
contract validation.

The migration must make SQL schema, queries, projections, parameter types, and
nullability compile-time checked. Domain and port models remain independent of
generated SQLDelight types: generated rows are infrastructure DTOs converted at
the adapter boundary, where persisted wire values and runtime contract versions
continue to be validated explicitly.

Room 3 remains the desktop-local database technology in
`runtime-desktop:core:database`. SQLDelight becomes the persistence technology
for the authoritative shared runtime database in `runtime-infra-sqlite`.

## Motivation

The current runtime database layer manually repeats each database contract
across `DatabaseSchema`, migrations, SQL strings, positional statement binds,
and `ResultSet.getString/getInt` mappings. The compiler cannot prove that those
representations agree. This creates avoidable failure modes:

- invalid SQL remains latent until a query executes;
- selected columns can drift from row mappers;
- nullability and SQLite integer/boolean conversions are repeated manually;
- positional bindings can be reordered incorrectly without a compile error;
- schema changes require broad string-based edits; and
- persistence behavior is obscured by plumbing rather than expressed as named
  queries and typed conversions.

SQLDelight addresses those problems without hiding SQL behind an ORM. Authored
`.sq` and `.sqm` files remain readable SQLite, while generated Kotlin owns
statement binding and result projection.

## Architecture Direction

The target dependency flow is:

```text
application/domain
       |
runtime-ports repository interfaces and domain-facing records
       |
runtime-infra-sqlite adapters and explicit wire-contract validation
       |
SQLDelight generated queries / transacter
       |
SQLite JDBC driver and the existing on-disk database
```

The runtime database remains repository-scoped through `DatabaseSessionFactory`
and `UnitOfWork`. A transaction must still cover every repository accessed in a
single `DatabaseSessionFactory.transaction` block, including during the mixed
migration period when some adapters use generated queries and others retain a
temporary JDBC escape hatch.

SQLDelight source and migration files become the eventual canonical schema and
query source for this module. Generated Kotlin is build output and must not be
committed. The existing Kotlin migration ledger remains authoritative until the
explicit migration-ownership phase proves existing-database adoption and
rollback behavior.

## Phased Delivery

1. **Foundation and equivalence harness** — pin SQLDelight, introduce schema and
   query source conventions, generate code, and build reusable repository,
   schema, and database-fixture parity tests without changing production wiring.
2. **Session and transaction bridge** — provide one database session capable of
   serving generated queries and temporary raw-JDBC adapters inside the same
   physical transaction; prove commit, rollback, PRAGMA, locking, and lifecycle
   equivalence before any repository cutover.
3. **Workflow-state persistence** — migrate feature-task, feature-verify,
   execution-identity, and session-summary reads/writes, retaining strict wire
   validation and durable continuation behavior.
4. **Work-list and learning persistence** — migrate typed work-list projections,
   review learnings, and session learnings.
5. **Telemetry, outbox, and reconciliation** — migrate lifecycle/quality/goal
   telemetry writes, outbox operations, reconciliation state, stale-session
   candidates, and recovery queries.
6. **Review persistence and statistics** — migrate review runs, findings,
   feedback, triage, health calculations, percentile/statistics queries, and
   dynamic reporting composition.
7. **Schema and migration ownership** — make checked SQLDelight schema/migrations
   authoritative, adopt existing databases safely, and retire duplicate Kotlin
   DDL/migration definitions only after fresh and historical fixtures agree.
8. **JDBC retirement and hardening** — remove obsolete manual mappings and
   helpers, constrain any justified low-level escape hatch, add architecture
   guards, update documentation, and run the complete validation suite.

## Acceptance Criteria

1. `runtime-infra-sqlite` uses a pinned stable SQLDelight release compatible
   with the repository's Kotlin, Gradle, configuration-cache, JVM, and SQLite
   versions. Generated sources are build output and are not committed.
2. Authored SQLDelight schema/query sources compile as part of the normal Gradle
   build. Invalid SQL, missing columns, incompatible query projections, invalid
   parameter usage, and migration gaps fail during build or migration
   verification rather than only at production query execution.
3. Existing `runtime-ports` persistence interfaces and domain-facing models stay
   free of SQLDelight, JDBC, generated-row, and SQLite-specific types. Generated
   records are converted inside `runtime-infra-sqlite` through small explicit
   mappers that preserve typed errors for unknown enums, invalid wire values,
   malformed JSON, missing required values, and contract-version drift.
4. `DatabaseSessionFactory.read` and `transaction` retain synchronous caller
   semantics. This feature does not introduce a repository-wide coroutine or
   reactive API migration.
5. One `DatabaseSessionFactory.transaction` block remains one atomic SQLite
   transaction across all repositories it exposes. During mixed migration,
   generated and temporary raw-JDBC operations share the same transactional
   boundary: a failure after writes through both paths rolls both back, and a
   successful block commits both exactly once.
6. Database opening preserves current operational behavior, including path
   resolution, parent-directory creation, `busy_timeout`, WAL journal mode,
   foreign-key enforcement, deterministic resource closure, and safe access by
   separate CLI/MCP/desktop processes. No process-local cache becomes an
   authority for durable state.
7. Fresh databases contain the same required tables, columns, constraints,
   defaults, foreign keys, and indexes as the pre-migration implementation.
   Schema-equivalence tests compare normalized SQLite metadata rather than
   relying only on happy-path CRUD tests.
8. Every supported historical database state migrates in place without data
   loss or destructive fallback. Existing `schema_migrations` records, workflow
   IDs, contract versions, JSON artifacts, timestamps, review data, learnings,
   telemetry, reconciliation markers, and pending outbox events remain readable
   and behaviorally equivalent after upgrade.
9. Workflow-state repositories no longer bind or decode normal CRUD/query rows
   through handwritten `PreparedStatement`/`ResultSet` calls. Feature-task mode
   validation, immutable execution identity, repository-scoped continuation,
   ambiguous candidate handling, ordering, limits, and feature-verify behavior
   remain unchanged.
10. Work-list, learning, telemetry, outbox, reconciliation, review, feedback,
    triage, and statistics repositories migrate to generated queries without
    changing their port-level results, ordering, filtering, pagination/limit
    semantics, idempotency, or typed failure behavior.
11. Complex statistics and reporting queries remain explicit SQL or compose
    only through typed/allowlisted fragments where SQLDelight cannot express a
    genuinely dynamic query. User-controlled identifiers or raw SQL fragments
    are never interpolated. Any retained dynamic seam has focused rejection and
    injection-safety tests.
12. Boolean, enum, timestamp, JSON, identifier, nullable, and collection
    conversions have one deliberate representation each. SQLite integer-backed
    booleans and domain enums are not re-decoded ad hoc throughout repositories.
13. The migration does not introduce N+1 query regressions or remove indexes
    used by continuation lookup, outbox polling, stale reconciliation, work-list
    ordering, feedback lookup, or review statistics. Representative query-plan
    or bounded-query-count tests protect the high-volume paths.
14. The final production module contains no handwritten row-to-model mapping via
    `ResultSet.get*` and no positional `PreparedStatement.set*` for ordinary
    repository operations. Low-level JDBC may remain only for a documented,
    narrowly allowlisted bootstrap, PRAGMA, compatibility, or migration need
    that SQLDelight cannot safely express.
15. Old Kotlin schema/migration sources are not removed until SQLDelight owns
    equivalent fresh creation and historical migration. There is never a phase
    where two migration systems can independently advance the same database
    version or partially apply competing migrations.
16. CLI, MCP, runtime, and desktop consumers require no database reset and keep
    their existing externally observable behavior. Golden output and workflow
    continuation artifacts remain unchanged unless an unrelated governed
    contract deliberately changes them.
17. Architecture and contributor documentation explains the SQLDelight source
    locations, generated-output boundary, entity/domain mapper policy, migration
    procedure, escape-hatch policy, and how to validate a schema change.
18. Tests include fresh database creation, every maintained historical migration
    fixture, mixed-path transaction commit/rollback, abrupt exception rollback,
    concurrent process access, corrupted durable rows, schema drift rejection,
    query ordering/limits, JSON and enum failures, and repository contract parity.
19. Each repository phase deletes the manual query/mapping code it replaces;
    the migration must not leave permanent parallel implementations selected by
    an unbounded runtime flag.
20. Maintainer validation passes:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Preserve SQLite as the storage engine and the current database path contract.
- Preserve the hexagonal module boundary: SQLDelight is an infrastructure
  implementation detail of `runtime-infra-sqlite`.
- Preserve blocking/synchronous repository ports unless a separately governed
  feature changes that application contract.
- Keep a single transaction authority. Never coordinate atomicity by opening a
  second connection and hoping operations commit together.
- Treat the on-disk database as user data. No destructive migration, implicit
  reset, or silent repair is allowed.
- Retain loud-fail typed validation at durable read seams; generated nullability
  does not replace semantic contract validation.
- Prefer named SQLDelight queries and generated projections over broad
  `SELECT *` queries.
- Keep authored SQL reviewable and SQLite-specific where that produces clearer,
  safer behavior; database portability is not a goal.
- Preserve unrelated SKILL-120 work already present in the worktree and account
  for its execution-identity/continuation schema in the migration baseline.
- Follow the comments policy: encode invariants through names, types, and tests;
  comment only non-obvious external constraints or compatibility reasons.

## Non-Goals

- Replacing Room 3 in `runtime-desktop:core:database`.
- Moving the authoritative database into the desktop module.
- Changing SQLite to PostgreSQL, an embedded document database, or a remote
  service.
- Redesigning workflow, telemetry, review, learning, or work-list domain models.
- Introducing coroutines, Flow, reactive persistence, or asynchronous ports.
- Normalizing JSON artifact envelopes into a new relational domain schema.
- Changing workflow contract versions merely because the persistence adapter
  changes.
- Combining this migration with unrelated UI, CLI, MCP, skill-content, or
  telemetry-product behavior changes.
- Keeping JDBC and SQLDelight as permanently interchangeable production
  implementations.

## Rollout And Recovery

This is an internal persistence implementation migration, not a user-selectable
feature, so a long-lived feature flag is not appropriate. Safety comes from
dependency-ordered commits, repository contract tests, historical database
fixtures, and preserving the ability to revert each subtask before schema
ownership moves.

Before subtask 7, reverting code must remain sufficient because the existing
schema/migration owner is retained. Subtask 7 must define and test the exact
upgrade boundary; after it lands, rollback compatibility must be explicit. If
the prior binary cannot safely read a database advanced by the new migration
owner, the change must loud-fail before migration or document a forward-only
boundary backed by fixture tests. No automatic downgrade is required.

## Validation Strategy

- Add reusable repository contract suites that run equivalent operations
  against the legacy adapter during the transition and the SQLDelight adapter.
- Snapshot normalized `sqlite_master`, `PRAGMA table_info`, foreign-key, index,
  and user/schema-version metadata for fresh and historical databases.
- Run transaction fault-injection tests that mix migrated and unmigrated
  repositories in one unit of work.
- Preserve and extend existing workflow, migration, telemetry, review, work-list,
  learning, CLI, MCP, and architecture tests.
- Test with real file-backed SQLite databases, not only in-memory databases,
  because WAL, locking, process concurrency, and migration behavior matter.
- Run focused checks after each subtask and the complete maintainer suite at the
  final gate.

## Next Path

Run `skill-bill goal SKILL-124`.
