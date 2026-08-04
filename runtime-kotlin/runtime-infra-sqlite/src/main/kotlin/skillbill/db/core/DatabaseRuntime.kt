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

  @Suppress("TooGenericExceptionCaught")
  fun openReadDb(
    cliValue: String? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): OpenDatabase {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    // Deliberate exception: an absent database is bootstrapped here because callers of the read seam
    // rely on first-use creation. Every existing database is opened without write capability below.
    if (!Files.exists(dbPath)) {
      return OpenDatabase(connection = ensureDatabase(dbPath), dbPath = dbPath)
    }

    val connection = try {
      DriverManager.getConnection(
        "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
        SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
      )
    } catch (error: SQLException) {
      throw databaseAccessError(dbPath, DatabaseAccessOperation.READ, error)
    }
    try {
      configureConnection(connection, enableWal = false)
      return OpenDatabase(connection = connection, dbPath = dbPath)
    } catch (error: SQLException) {
      connection.closeQuietly()
      throw databaseAccessError(dbPath, DatabaseAccessOperation.READ, error)
    } catch (error: Throwable) {
      connection.close()
      throw error
    }
  }

  @Suppress("TooGenericExceptionCaught")
  fun ensureDatabase(path: Path): Connection {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    val connection = try {
      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
    } catch (error: SQLException) {
      throw databaseAccessError(path, DatabaseAccessOperation.OPEN, error)
    }
    try {
      configureConnection(connection, enableWal = true)
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(connection)
      DatabaseColumnMigrations.apply(connection)
      DatabaseColumnMigrations.healWorkListMetadata(connection)
      return connection
    } catch (error: SQLException) {
      connection.closeQuietly()
      throw databaseAccessError(path, DatabaseAccessOperation.OPEN, error)
    } catch (error: Throwable) {
      connection.close()
      throw error
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
