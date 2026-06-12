package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.db.DatabaseRuntime
import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

@Inject
class SQLiteDatabaseSessionFactory(
  private val context: EnvironmentContext,
) : DatabaseSessionFactory {
  private val resolvedContext = context.withProcessDefaults()

  override fun resolveDbPath(dbOverride: String?) = DatabaseRuntime.resolveDbPath(
    cliValue = dbOverride ?: resolvedContext.dbPathOverride,
    environment = resolvedContext.environment,
    userHome = resolvedContext.userHome,
  )

  override fun databaseExists(dbOverride: String?): Boolean = Files.exists(resolveDbPath(dbOverride))

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openDb(
    cliValue = dbOverride ?: resolvedContext.dbPathOverride,
    environment = resolvedContext.environment,
    userHome = resolvedContext.userHome,
  ).use { openDb ->
    block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openDb(
    cliValue = dbOverride ?: resolvedContext.dbPathOverride,
    environment = resolvedContext.environment,
    userHome = resolvedContext.userHome,
  ).use { openDb ->
    openDb.connection.inTransaction {
      block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
    }
  }
}

private fun EnvironmentContext.withProcessDefaults(): EnvironmentContext {
  val withUserHome =
    if (userHome == EnvironmentContext.UnspecifiedUserHome) {
      copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
    } else {
      copy(userHome = userHome.toAbsolutePath().normalize())
    }
  return if (withUserHome.environment === EnvironmentContext.UnspecifiedEnvironment) {
    withUserHome.copy(environment = System.getenv())
  } else {
    withUserHome
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
