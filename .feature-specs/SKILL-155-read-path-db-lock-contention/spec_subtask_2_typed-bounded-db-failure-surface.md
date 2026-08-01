# SKILL-155 Subtask 2 - Typed Bounded Database Failure Surface

Parent spec: [.feature-specs/SKILL-155-read-path-db-lock-contention/spec.md](./spec.md)
Issue key: SKILL-155

## Scope

Replace the uncaught `org.sqlite.SQLiteException` that escapes to `skillbill.cli.core.MainKt.main` with a typed, bounded, loud-fail surface. A read that genuinely cannot proceed reports a typed error carrying the database path and the underlying SQLite condition, and `skill-bill goal status --monitor` renders it as a bounded payload consistent with its read-only snapshot contract.

Primary files: the database session seam under `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/`, the corresponding typed error in the ports or contracts layer, and `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt`.

## Acceptance Criteria

1. A database open or read that fails on a SQLite condition raises a typed error carrying the resolved database path and the underlying condition; the raw JDBC exception is not propagated past the infrastructure boundary.
2. No `org.sqlite.SQLiteException` and no JDBC stack trace reaches the CLI boundary or user-visible output on this path.
3. `skill-bill goal status --monitor` renders the typed failure as a bounded payload matching its existing bounded-status shape, and exits non-zero.
4. The failure is distinguishable from `not_found` and from a healthy snapshot, so a contended read is never presented as absent or empty state.
5. Bounded output rules hold: the payload carries no raw child output, no stack frames, and no unbounded diagnostic text.
6. A test asserts the typed bounded failure surface for a contended or unopenable database and fails when the typed handling is reverted, leaving the JDBC exception to escape.

## Non-Goals

- Changing when reads acquire locks; that is Subtask 1.
- Adding retry, backoff, or polling to recover from contention.
- Changing goal status projection fields or the `bill-monitor` skill contract beyond the failure surface.
- Broadening typed error handling to unrelated CLI commands or non-database failure paths.

## Dependency Notes

Independent of Subtask 1 and may land before or after it. The typed surface remains necessary after Subtask 1, because a read can still fail for reasons other than avoidable lock contention, such as a corrupt or unreadable database file.

## Validation Strategy

Add a CLI-level test that drives the monitor status path against a database that cannot be read and asserts the bounded typed payload and non-zero exit. Confirm it fails with the typed handling reverted. Run the CLI and infrastructure test suites, then `(cd runtime-kotlin && ./gradlew check)`, `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Next Path

Commit this subtask. With both subtasks complete, confirm the parent acceptance criteria and close SKILL-155.
