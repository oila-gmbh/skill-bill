# SKILL-168 · Subtask 2 — Consistent read snapshot for ide-status candidate collection

## Scope

Remove the runtime's ability to produce a spurious `no_matching_work` for a live, correctly
bound goal by giving `IdeStatusService`'s candidate collection a single consistent database
snapshot.

Today `DatabaseSessionFactory.read` opens a read-only connection and invokes the block
directly, with no transaction:

`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SQLiteDatabaseSessionFactory.kt:29-39`

```kotlin
override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openReadDb(...)
  .use { openDb -> try { block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath)) } ... }
```

Contrast `transaction` (`:49-57`), which wraps the block in `BEGIN IMMEDIATE` via
`inTransaction` (`:75-85`). Because `read` has no `BEGIN`, every statement inside the block
runs in its own implicit read transaction, so the dozens of SELECTs in
`IdeStatusService.collectCandidates` (`runtime-application/.../work/IdeStatusService.kt:91-138`
plus the per-candidate repository resolution at `:144-272`) have **no cross-statement
snapshot**. A writer committing partway through yields a torn view. When that tearing drops
the only candidate, `select` returns null and `IdeStatusService.kt:58-59` emits the
`no_matching_work` problem snapshot for a goal that is durably running.

### Design decision: deferred read transaction, and why it is safe here

Wrap the read block in `BEGIN DEFERRED` / `COMMIT` so all statements share one snapshot. Do
**not** use `BEGIN IMMEDIATE` — that acquires a write lock and would make a read path
contend with the goal runtime's writers.

The database runs in **WAL** journal mode (verified against the live
`~/.skill-bill/review-metrics.db`: `pragma journal_mode` → `wal`, with an active `-shm`).
Under WAL a read transaction sees a stable snapshot *without* blocking concurrent writers, so
holding the snapshot open across the whole collection does not stall the goal runtime. Note
that `openReadDb` does not itself set `journal_mode` (`db/core/DatabaseRuntime.kt:53-64,
100-107`); the mode is a property of the database file, not the connection, so this must be
verified rather than assumed — if a database is ever opened in rollback-journal mode, a long
read transaction *would* block writers, and that tradeoff needs to be handled rather than
silently accepted.

Apply this at the `read` seam so every read-path consumer benefits, rather than special-casing
`IdeStatusService`. Confirm no existing `read` caller depends on observing mid-block writes —
a read block that expects to see its own concurrent writer's commits would change behavior.

### Secondary hardening

`WorkflowStateStore.kt:396-398` reads execution identities in one statement, then fetches each
workflow row in another, and calls `error("Feature-task identity '<id>' has no workflow row.")`
when the second lookup misses. Under a consistent snapshot that mismatch becomes unreachable
from concurrent deletion. Confirm this while scoping; if it remains reachable for genuinely
orphaned rows, it should fail as a typed, caught error rather than an uncaught
`IllegalStateException` escaping `IdeStatusService`'s catch list (which handles only
`InvalidWorkListRowError` and `InvalidWorkflowStateSchemaError`, `IdeStatusService.kt:72-79`).

## Acceptance Criteria

1. `DatabaseSessionFactory.read`'s block observes a single consistent database snapshot: all
   statements issued inside one `read` block see the same committed state, and a writer commit
   during the block is not partially visible.
2. The snapshot is acquired without taking a write lock, so a concurrent writer is not blocked
   for the duration of a read block.
3. A concurrent writer committing at any point during `IdeStatusService`'s candidate
   collection cannot cause a live, correctly-bound, durably `running` goal to be reported as
   `no_matching_work`. This holds regardless of which statement pair the commit lands between.
4. A repository that genuinely has no matching work still returns the `no_matching_work`
   problem snapshot, unchanged in shape, message, `lifecycle_state`, `freshness`, and exit
   code 0.
5. Read-path failures remain typed: a failure acquiring or releasing the snapshot surfaces as
   a `DatabaseAccessOperation.READ` access error and never leaks a raw `SQLException` or an
   uncaught `IllegalStateException` out of `IdeStatusService`.
6. `IdeStatusSelectionPolicy` is unchanged — no change to retention windows, tier ranking, or
   ordering — and no ide-status wire, schema, or `contract_version` change is made.
7. Existing runtime tests stay green, including `IdeStatusServiceTest`,
   `IdeStatusGoldenFixturesTest`, `IdeStatusSchemaValidatorTest`, and
   `IdeStatusSchemaContractVersionTest`.

## Non-Goals

- No change to the meaning, message, or wire shape of the `no_matching_work` problem code.
- No change to `IdeStatusSelectionPolicy` retention or ordering.
- No change to goal planning, checkpointing, or `planningStatus` computation.
- No conversion of the read path to a write path, and no `BEGIN IMMEDIATE` on reads.
- No change to `selfManagedWrite` or `transaction` semantics.
- No new CLI commands, flags, or MCP tools.

## Dependency Notes

Ordered after subtask 1 but has **no code dependency** on it — the two touch disjoint modules
(`runtime-kotlin/runtime-infra-sqlite` and `runtime-application` here; `intellij-plugin` there).
Subtask 1 ships the user-visible fix; this subtask removes the root cause so the plugin's
smoothing is a safety net rather than a workaround.

## Validation Strategy

- A concurrency test that interleaves a writer commit with candidate collection — e.g. a latch
  or an instrumented `UnitOfWork` that commits a mutation between two collection statements —
  asserting the resulting snapshot still reports the live goal. Written against the *mechanism*
  (a commit landing mid-collection) rather than one specific torn statement pair, per AC 3.
- A test asserting a genuinely empty repository still produces the exact `no_matching_work`
  snapshot (AC 4), guarding against over-correction.
- A test that a read-path SQL failure surfaces as a typed `READ` access error (AC 5).
- Verify `pragma journal_mode` is WAL for the databases the read path opens, and cover the
  non-WAL case explicitly if it is reachable.
- Full runtime test suite plus the ide-status golden fixtures and schema contract tests.

## Next Path

Goal complete. If the sampler output referenced in the parent spec's Open Item lands before
implementation, fold the captured torn window into this subtask's concurrency test as a
concrete regression case — without narrowing AC 3, which must stay mechanism-level.
