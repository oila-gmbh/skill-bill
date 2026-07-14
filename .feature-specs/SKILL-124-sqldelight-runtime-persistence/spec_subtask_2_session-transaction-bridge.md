---
status: Ready
parent_spec: ./spec.md
subtask_id: 2
---

# SKILL-124 · Subtask 2 — Session and transaction bridge

## Scope

Make SQLDelight available through the production SQLite session while retaining
one atomic unit of work across migrated and not-yet-migrated repositories.

1. Introduce the module-owned SQLDelight database/session abstraction and wire
   it through `SQLiteDatabaseSessionFactory` and `SQLiteUnitOfWork` without
   leaking generated or driver types through `runtime-ports`.
2. Ensure generated queries and temporary JDBC adapters use the same physical
   connection and transaction. If an internal connection-bound `SqlDriver`
   adapter is necessary, keep it minimal, infrastructure-private, and covered
   against SQLDelight driver semantics; do not create another general database
   abstraction.
3. Preserve read versus transaction behavior, exactly-once commit/rollback,
   nested-call policy, exception propagation, and deterministic close ordering.
4. Preserve database path resolution, directory creation, JDBC driver loading,
   WAL, busy timeout, foreign keys, and multi-process file access.
5. Add fault-injection tests combining one generated write and one legacy JDBC
   write in both orders, with failures before and after each write.
6. Add lifecycle and concurrency tests for repeated opens, concurrent readers,
   competing writers within the busy timeout, and no leaked locks/connections.

## Acceptance Criteria (this subtask)

1. `DatabaseSessionFactory` and `UnitOfWork` public signatures remain unchanged
   and synchronous.
2. Generated and legacy repository operations inside one transaction share one
   atomic commit/rollback boundary; no second independent connection performs a
   transactional write.
3. Exceptions of every currently handled category roll the entire transaction
   back and preserve the original failure.
4. Read sessions never accidentally commit writes, and transaction sessions
   commit once only after a successful block.
5. WAL, `busy_timeout`, foreign keys, path creation, and close behavior match the
   legacy `DatabaseRuntime` behavior.
6. Repeated and concurrent file-backed tests leave no database locks or resource
   leaks and preserve supported separate-process access.
7. Production repositories still behave identically; this phase changes session
   infrastructure, not repository results.

## Non-Goals

- Migrating repository SQL beyond the representative foundation slice.
- Moving schema/migration ownership to SQLDelight.
- Adding pooling, asynchronous I/O, or reactive invalidation.
- Exposing a raw connection to application/domain callers.

## Dependencies

- Subtask 1 must be complete.

## Validation Strategy

- Run the new mixed-path transaction matrix against file-backed SQLite.
- Run existing `DatabaseSessionFactory`, migration, workflow, telemetry, and
  repository tests unchanged.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check :runtime-core:check`.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_2_session-transaction-bridge.md`.
