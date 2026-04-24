package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.db.DatabaseRuntime
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Files
import java.sql.Connection

@Inject
class SQLiteDatabaseSessionFactory(
  private val context: RuntimeContext,
) : DatabaseSessionFactory {
  override fun resolveDbPath(dbOverride: String?) = DatabaseRuntime.resolveDbPath(
    cliValue = dbOverride ?: context.dbPathOverride,
    environment = context.environment,
    userHome = context.userHome,
  )

  override fun databaseExists(dbOverride: String?): Boolean = Files.exists(resolveDbPath(dbOverride))

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openDb(
    cliValue = dbOverride ?: context.dbPathOverride,
    environment = context.environment,
    userHome = context.userHome,
  ).use { openDb ->
    block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openDb(
    cliValue = dbOverride ?: context.dbPathOverride,
    environment = context.environment,
    userHome = context.userHome,
  ).use { openDb ->
    openDb.connection.inTransaction {
      block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
    }
  }
}

private fun <T> Connection.inTransaction(block: () -> T): T {
  val previousAutoCommit = autoCommit
  autoCommit = false
  try {
    val outcome = runCatching(block)
    if (outcome.isSuccess) {
      commit()
    } else {
      rollback()
    }
    return outcome.getOrThrow()
  } finally {
    autoCommit = previousAutoCommit
  }
}
