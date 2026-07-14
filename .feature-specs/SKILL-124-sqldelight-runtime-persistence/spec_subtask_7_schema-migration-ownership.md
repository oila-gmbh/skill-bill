---
status: Ready
parent_spec: ./spec.md
subtask_id: 7
---

# SKILL-124 · Subtask 7 — Schema and migration ownership

## Scope

Transfer fresh-schema creation and historical migration ownership to verified
SQLDelight sources after all production repository queries have migrated.

1. Make the SQLDelight schema the canonical latest schema for every maintained
   table, index, constraint, default, trigger if any, and foreign key.
2. Translate maintained historical schema changes into ordered `.sqm` migrations
   or SQLDelight migration callbacks where a data transformation genuinely
   requires Kotlin.
3. Define the adoption path for databases currently tracked by
   `schema_migrations`, including the exact relationship to SQLite
   `PRAGMA user_version` and SQLDelight schema versioning.
4. Ensure adoption is atomic and idempotent: an existing valid database is
   stamped only after its schema/history is verified, while a partial, unknown,
   or corrupt state loud-fails without advancing either version marker.
5. Preserve custom feedback normalization, goal telemetry creation, work-list
   metadata, feature-task execution identity, healing, and any other maintained
   data transformations.
6. Prove fresh-schema and every maintained historical-fixture equivalence using
   normalized SQLite metadata and representative data assertions.
7. Remove `DatabaseSchema`, `DatabaseMigrations`, and
   `DatabaseColumnMigrations` only when no runtime seam or supported fixture
   depends on them; retain narrowly named callbacks for transformations that
   cannot be safe declarative SQL.
8. Define downgrade/revert compatibility at the ownership boundary and ensure
   older binaries fail safely if they cannot understand the advanced database.

## Acceptance Criteria (this subtask)

1. New databases are created solely from the governed SQLDelight schema path and
   match all required legacy metadata and behavior.
2. Every maintained historical fixture upgrades in place with all records and
   contract values preserved.
3. Existing `schema_migrations` history is adopted deterministically; version
   markers cannot disagree silently and no migration executes twice.
4. Unknown, missing, partially applied, or tampered migration history loud-fails
   before schema version advancement or repository use.
5. Data-transforming migrations preserve their rejection, rollback, and
   idempotency behavior.
6. Fresh creation and migration occur inside appropriate atomic transactions and
   tolerate supported concurrent process startup without two migrators racing.
7. Duplicate Kotlin DDL and migration orchestration is removed after equivalence
   is proven; remaining callbacks contain only transformations SQLDelight cannot
   express safely.
8. The rollback/forward-only boundary is documented and tested with the prior
   supported database opener.

## Non-Goals

- Supporting arbitrary undocumented database snapshots.
- Automatic downgrade migrations.
- Redesigning tables or normalizing domain data during the ownership transfer.
- Destructive fallback or database reset.

## Dependencies

- Subtasks 1 through 6 must be complete.

## Validation Strategy

- Maintain fixture databases at each historical migration version plus malformed
  and partially applied variants.
- Compare normalized tables, columns, defaults, checks, foreign keys, indexes,
  and version metadata after migration.
- Inject failures midway through schema and data migrations and assert full
  rollback plus unchanged version markers.
- Start two migrators against one file-backed database and assert deterministic
  success/failure without corruption.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check` before the full
  repository suite.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_7_schema-migration-ownership.md`.
