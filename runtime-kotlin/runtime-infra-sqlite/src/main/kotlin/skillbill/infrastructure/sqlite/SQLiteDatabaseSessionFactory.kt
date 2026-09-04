package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.databaseAccessError
import skillbill.error.DatabaseAccessOperation
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

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

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openReadDb(
    cliValue = dbOverride ?: resolvedContext.dbPathOverride,
    environment = resolvedContext.environment,
    userHome = resolvedContext.userHome,
  ).use { openDb ->
    try {
      openDb.connection.inReadTransaction(openDb.dbPath) {
        block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
      }
    } catch (error: SQLException) {
      throw databaseAccessError(openDb.dbPath, DatabaseAccessOperation.READ, error)
    }
  }

  override fun <T> readIfPresent(dbOverride: String?, block: (UnitOfWork) -> T): T? =
    DatabaseRuntime.openReadDbIfPresent(
      cliValue = dbOverride ?: resolvedContext.dbPathOverride,
      environment = resolvedContext.environment,
      userHome = resolvedContext.userHome,
    )?.use { openDb ->
      try {
        openDb.connection.inReadTransaction(openDb.dbPath) {
          block(SQLiteUnitOfWork(openDb.connection, openDb.dbPath))
        }
      } catch (error: SQLException) {
        throw databaseAccessError(openDb.dbPath, DatabaseAccessOperation.READ, error)
      }
    }

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = DatabaseRuntime.openDb(
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
    openDb.connection.inTransaction(openDb.dbPath) {
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

private fun <T> Connection.inTransaction(dbPath: Path, block: () -> T): T {
  typedStatement(dbPath, "BEGIN IMMEDIATE", DatabaseAccessOperation.OPEN)
  var committed = false
  return try {
    val result = block()
    typedStatement(dbPath, "COMMIT", DatabaseAccessOperation.OPEN)
    committed = true
    result
  } finally {
    if (!committed) {
      runCatching { createStatement().use { it.execute("ROLLBACK") } }
    }
  }
}

/**
 * BEGIN DEFERRED — never IMMEDIATE — gives every statement in the block one consistent snapshot
 * without taking a write lock, so a concurrent goal-runtime writer is not blocked for the read's
 * duration. Acquisition and release failures classify as READ, not OPEN.
 */
private fun <T> Connection.inReadTransaction(dbPath: Path, block: () -> T): T {
  typedStatement(dbPath, "BEGIN DEFERRED", DatabaseAccessOperation.READ)
  var committed = false
  return try {
    val result = block()
    typedStatement(dbPath, "COMMIT", DatabaseAccessOperation.READ)
    committed = true
    result
  } finally {
    if (!committed) {
      runCatching { createStatement().use { it.execute("ROLLBACK") } }
    }
  }
}

private fun Connection.typedStatement(dbPath: Path, sql: String, operation: DatabaseAccessOperation) {
  try {
    createStatement().use { it.execute(sql) }
  } catch (error: SQLException) {
    throw databaseAccessError(dbPath, operation, error)
  }
}
