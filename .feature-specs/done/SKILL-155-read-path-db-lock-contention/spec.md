# SKILL-155 - Read-Path Database Lock Contention

## Mode

decomposed

## Intended Outcome

A read-only workflow-database snapshot succeeds while another process holds the write lock. Monitoring a goal is possible for the whole time that goal is running, which is the only time monitoring is useful. When a read genuinely cannot proceed, the caller receives a typed, bounded failure instead of a raw Java stack trace.

## Overview

`DatabaseRuntime.ensureDatabase` runs the full migration pipeline on every connection open, including read-only ones. `DatabaseMigrations.apply` opens a `BEGIN IMMEDIATE` write transaction before checking whether any migration is pending, so a read path acquires a write lock it never needs. With a concurrent goal run holding the write lock for longer than the 5000 ms `busy_timeout`, every read-only open fails.

The failure was reproduced against a live `skill-bill goal SKILL-10 --agent codex --no-live-output` process: `skill-bill goal status SKILL-10 --monitor` failed on four consecutive attempts across roughly a minute, while a plain read-only SQLite connection (`file:...?mode=ro`) read the same tables successfully. The data was readable throughout; only the unnecessary write lock blocked it.

The workflow database at `~/.skill-bill/review-metrics.db` is shared across repositories, and running a goal in one checkout while monitoring from another is the normal operating pattern. A goal run in any repository therefore blocks read-only monitoring in every repository for its full duration.

The resulting `org.sqlite.SQLiteException` is uncaught. It escapes to `skillbill.cli.core.MainKt.main` and prints a JDBC stack trace, which violates the `bill-monitor` bounded read-only snapshot contract.

## Acceptance Criteria

1. A read-only database open succeeds while a separate connection holds a write lock on the same database, with no dependency on `busy_timeout` expiry.
2. `DatabaseMigrations.apply` determines whether any migration is pending using reads only, and opens its `BEGIN IMMEDIATE` transaction solely when there is migration work to perform.
3. When migration work is pending, the pending set is re-derived inside the write transaction, so two processes racing the same migration remain correct and the loser applies nothing twice.
4. A database with no `schema_migrations` table, an empty database, and a database whose ledger is still version-keyed all continue to migrate correctly through the gated path.
5. Read-only session paths obtain a connection that does not require write capability, and a read-only path never performs schema mutation as a side effect of opening.
6. Unconditional column-heal behavior remains intact for write paths: a column appended to an already-applied migration body is still healed on startup for existing databases.
7. A read that cannot proceed fails with a typed error carrying the database path and the underlying SQLite condition; no `org.sqlite.SQLiteException` or JDBC stack trace reaches the CLI boundary.
8. `skill-bill goal status --monitor` renders that typed failure as a bounded payload consistent with its read-only snapshot contract, and exits non-zero.
9. Tests fail without their fix: a concurrent-writer test proving a read-only open succeeds against a held write lock, and a test asserting the typed bounded failure surface rather than an escaped JDBC exception.
10. Whether the goal runner holds long-lived write transactions spanning child agent execution is determined and recorded, since a 5000 ms `busy_timeout` never cleared across a minute of retries. Any separate defect found is written up rather than silently folded into this work.
11. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Constraints

- Migration correctness outranks read availability. Skipping the write transaction is permitted only when reads prove there is nothing to apply.
- The self-healing column-migration design is deliberate and stays: `DatabaseColumnMigrations.apply` and `healWorkListMetadata` keep running on write-capable startup paths.
- Preserve the documented ordering in `ensureDatabase`: `busy_timeout` before `journal_mode` so the WAL switch tolerates a concurrent writer.
- The workflow database stays authoritative. No caching, snapshotting, or copying to sidestep contention.
- Loud-fail conventions apply. A read that cannot proceed reports a typed error; it must not degrade to an empty or partial snapshot presented as real state.

## Non-Goals

- Replacing SQLite, changing the journal mode, or moving to a client/server database.
- Adding retry, backoff, or polling loops to the monitor path to paper over contention.
- Redesigning the goal runner's transaction boundaries. Criterion 10 is investigation and write-up, not a rewrite.
- Changing goal status projection content, monitor output fields, or the `bill-monitor` skill contract beyond the failure surface.
- Splitting the shared workflow database per repository.

## Validation Strategy

Add a concurrent-writer integration test in the SQLite infrastructure module that holds a write lock on one connection and asserts a successful read-only open on another, and a fresh/empty/version-keyed-ledger migration test proving the gated path still migrates. Add a CLI-level test asserting the typed bounded failure payload. Confirm each new test fails with the fix reverted. Then run `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.
