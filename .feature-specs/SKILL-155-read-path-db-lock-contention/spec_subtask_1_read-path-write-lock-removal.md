# SKILL-155 Subtask 1 - Read-Path Write-Lock Removal

Parent spec: [.feature-specs/SKILL-155-read-path-db-lock-contention/spec.md](./spec.md)
Issue key: SKILL-155

## Scope

Stop read-only database opens from acquiring a write lock. Gate the `BEGIN IMMEDIATE` transaction in `DatabaseMigrations.apply` behind a reads-only pending-migration check, re-derive the pending set inside the lock so racing processes stay correct, and give read-only session paths a connection that does not require write capability. Determine and record whether the goal runner holds long-lived write transactions spanning child agent execution.

Primary files: `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseRuntime.kt`, `DatabaseMigrations.kt`, `MigrationLedger.kt`, and `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SQLiteDatabaseSessionFactory.kt`.

## Acceptance Criteria

1. `DatabaseMigrations.apply` computes the pending-migration set using reads only and returns without opening a transaction when nothing is pending.
2. When migration work is pending, `apply` opens `BEGIN IMMEDIATE` and re-derives the pending set inside that transaction, so a process that loses the race applies nothing twice.
3. A database with no `schema_migrations` table, an empty database, and a database whose ledger is still version-keyed each migrate correctly through the gated path; the version-keyed rebuild in `MigrationLedger.ensureNameKeyed` still runs under the write lock.
4. The read-only session path acquires a connection without write capability and performs no schema mutation as a side effect of opening.
5. `DatabaseColumnMigrations.apply` and `healWorkListMetadata` continue to run unconditionally on write-capable startup paths, preserving the self-healing column contract for existing databases.
6. A concurrent-writer test holds a write lock on one connection and asserts a successful read-only open and query on another, and fails when the gating change is reverted.
7. Findings on goal-runner write-transaction lifetime are recorded: either evidence that transactions are short and contention comes only from open frequency, or a described separate defect with the holding call path named.

## Non-Goals

- Changing the CLI failure surface or introducing the typed error type; that is Subtask 2.
- Redesigning goal-runner transaction boundaries. Criterion 7 is investigation and write-up only.
- Changing journal mode, `busy_timeout`, or the ordering of pragma statements in `ensureDatabase`.
- Adding retry or backoff loops anywhere on the read path.

## Dependency Notes

Independent of Subtask 2 and safe to land first or second. Subtask 2 does not require this change to be merged, since the typed failure surface must exist for the residual cases where a read genuinely cannot proceed.

## Validation Strategy

Run the SQLite infrastructure test suites including `DatabaseMigrationsTest`, `DatabaseSchemaTest`, and `WorkflowStateStoreTest`. Verify the new concurrent-writer test fails with the gating reverted. Then run `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate`.

## Next Path

Commit this subtask, then execute Subtask 2: typed bounded database failure surface.
