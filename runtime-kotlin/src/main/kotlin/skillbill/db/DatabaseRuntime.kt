package skillbill.db

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

  fun ensureDatabase(path: Path): Connection {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
    connection.createStatement().use { statement ->
      statement.execute("PRAGMA foreign_keys = ON")
    }
    DatabaseSchema.createBaseSchema(connection)
    DatabaseMigrations.apply(connection)
    return connection
  }
}
