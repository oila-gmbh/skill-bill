package skillbill.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseSchemaTest {
  @Test
  fun `ensureDatabase creates parent directories enables foreign keys and bootstraps schema`() {
    val tempDir = Files.createTempDirectory("runtime-kotlin-db-schema")
    val dbPath = tempDir.resolve("nested").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val foreignKeysEnabled =
        connection.createStatement().use { statement ->
          statement.executeQuery("PRAGMA foreign_keys").use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }
      val tables = sqliteObjects(connection = connection, type = "table")
      val indexes = sqliteObjects(connection = connection, type = "index")

      assertTrue(Files.isDirectory(dbPath.parent))
      assertTrue(Files.exists(dbPath))
      assertEquals(1, foreignKeysEnabled)
      assertTrue(
        DatabaseSchema.tableNames.all { it in tables },
        "Missing tables: ${DatabaseSchema.tableNames - tables}",
      )
      assertTrue(
        DatabaseSchema.indexNames.all { it in indexes },
        "Missing indexes: ${DatabaseSchema.indexNames - indexes}",
      )
    }
  }

  private fun sqliteObjects(connection: java.sql.Connection, type: String): Set<String> = connection.prepareStatement(
    """
      SELECT name
      FROM sqlite_master
      WHERE type = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, type)
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }
}
