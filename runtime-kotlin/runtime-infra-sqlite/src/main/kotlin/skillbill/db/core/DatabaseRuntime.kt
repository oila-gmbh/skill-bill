package skillbill.db.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

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
    if (!Files.exists(dbPath)) {
      return OpenDatabase(connection = ensureDatabase(dbPath), dbPath = dbPath)
    }

    val connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}")
    try {
      configureConnection(connection, enableWal = false)
      return OpenDatabase(connection = connection, dbPath = dbPath)
    } catch (error: Throwable) {
      connection.close()
      throw error
    }
  }

  @Suppress("TooGenericExceptionCaught")
  fun ensureDatabase(path: Path): Connection {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
    try {
      configureConnection(connection, enableWal = true)
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(connection)
      DatabaseColumnMigrations.apply(connection)
      DatabaseColumnMigrations.healWorkListMetadata(connection)
      return connection
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
