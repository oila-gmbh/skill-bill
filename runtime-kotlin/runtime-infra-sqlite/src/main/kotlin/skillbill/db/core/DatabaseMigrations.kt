package skillbill.db.core

import java.sql.Connection

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    (databaseMigrationsEarly + databaseMigrationsLate).also(::requireDeterministicMigrations)

  fun apply(connection: Connection) {
    // Optimistic reads-only pre-check: every open would otherwise take the write lock just to learn
    // there is nothing to do. The in-lock re-derivation below stays the sole authority.
    val ledger = MigrationLedger.readState(connection)
    if (!ledger.hasPendingWork(migrations.map { migration -> migration.name })) return

    connection.inImmediateTransaction {
      MigrationLedger.ensureNameKeyed(this)
      val appliedNames = MigrationLedger.appliedNames(this)
      migrations
        .filterNot { migration -> migration.name in appliedNames }
        .forEach { migration ->
          migration.apply(this)
          MigrationLedger.record(this, migration)
        }
    }
  }

  private fun requireDeterministicMigrations(migrations: List<DatabaseMigration>) {
    val versions = migrations.map { migration -> migration.version }
    val names = migrations.map { migration -> migration.name }

    require(versions == versions.sorted()) { "Database migrations must be ordered by version." }
    require(versions.toSet().size == versions.size) { "Database migration versions must be unique." }
    require(names.toSet().size == names.size) { "Database migration names must be unique." }
  }
}
