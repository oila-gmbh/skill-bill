package skillbill.db.core

import org.sqlite.SQLiteConfig
import skillbill.error.DatabaseAccessError
import skillbill.error.DatabaseAccessOperation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

data class OpenDatabase(
  val connection: Connection,
  val dbPath: Path,
) : AutoCloseable {
  override fun close() {
    connection.close()
  }
}

object DatabaseRuntime {
  fun resolveDbPath(
    cliValue: String?,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): Path = DatabasePaths.resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)

  fun openDb(
    cliValue: String? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): OpenDatabase {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    return OpenDatabase(connection = ensureDatabase(dbPath), dbPath = dbPath)
  }

  fun openReadDb(
    cliValue: String? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): OpenDatabase {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    if (!Files.exists(dbPath) || isSchemaless(dbPath)) {
      return OpenDatabase(connection = ensureDatabase(dbPath), dbPath = dbPath)
    }
    return openReadOnlyDb(dbPath)
  }

  fun openReadDbIfPresent(
    cliValue: String? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): OpenDatabase? {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    if (!Files.exists(dbPath)) return null
    if (isSchemaless(dbPath)) {
      throw DatabaseAccessError(
        dbPath = dbPath.toAbsolutePath().normalize().toString(),
        operation = DatabaseAccessOperation.READ,
        condition = "database schema is missing",
      )
    }
    return openReadOnlyDb(dbPath)
  }

  private fun openReadOnlyDb(dbPath: Path): OpenDatabase {
    val connection = asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
      DriverManager.getConnection(
        "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
        SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
      )
    }
    return connection.closingOnFailure {
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        configureConnection(connection, enableWal = false)
      }
      OpenDatabase(connection = connection, dbPath = dbPath)
    }
  }

  fun ensureDatabase(path: Path): Connection {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    val connection = asTypedFailure(path, DatabaseAccessOperation.OPEN) {
      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
    }
    return connection.closingOnFailure {
      asTypedFailure(path, DatabaseAccessOperation.OPEN) {
        configureConnection(connection, enableWal = true)
        DatabaseSchema.createBaseSchema(connection)
        DatabaseMigrations.apply(connection)
        DatabaseColumnMigrations.apply(connection)
        DatabaseColumnMigrations.healDiagnosticEvidenceKeys(connection)
        DatabaseColumnMigrations.healWorkListMetadata(connection)
      }
      connection
    }
  }

  // A zero-byte file is a valid empty SQLite database with no tables, so emptiness of sqlite_master
  // is the check rather than file size: it also catches a store whose creation was interrupted after
  // the file appeared but before createBaseSchema committed.
  private fun isSchemaless(dbPath: Path): Boolean = asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
    DriverManager.getConnection(
      "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
      SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
    ).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'").use { resultSet ->
          resultSet.next() && resultSet.getInt(1) == 0
        }
      }
    }
  }

  private fun configureConnection(connection: Connection, enableWal: Boolean) {
    connection.createStatement().use { statement ->
      // busy_timeout before journal_mode so the WAL switch tolerates a concurrent writer on the shared DB.
      statement.execute("PRAGMA busy_timeout = 5000")
      if (enableWal) statement.execute("PRAGMA journal_mode = WAL")
      statement.execute("PRAGMA foreign_keys = ON")
    }
  }
}

private fun <T> asTypedFailure(dbPath: Path, operation: DatabaseAccessOperation, block: () -> T): T = try {
  block()
} catch (error: SQLException) {
  throw databaseAccessError(dbPath, operation, error)
}

@Suppress("TooGenericExceptionCaught")
private fun <T> Connection.closingOnFailure(block: () -> T): T = try {
  block()
} catch (error: Throwable) {
  closeQuietly()
  throw error
}

internal fun databaseAccessError(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  error: SQLException,
): DatabaseAccessError = DatabaseAccessError(
  dbPath = dbPath.toAbsolutePath().normalize().toString(),
  operation = operation,
  condition = "sqlite result code ${error.errorCode}: ${error.message.orEmpty()}",
)

@Suppress("SwallowedException")
internal fun Connection.closeQuietly() {
  try {
    close()
  } catch (ignored: SQLException) {
    // A failed close on an already-broken connection must not mask the typed access error.
  }
}
