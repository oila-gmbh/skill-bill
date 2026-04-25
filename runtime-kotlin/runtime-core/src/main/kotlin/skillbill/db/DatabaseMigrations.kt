package skillbill.db

import java.sql.Connection
import java.sql.SQLException

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    listOf(
      DatabaseMigration(
        version = 1,
        name = "add-review-workflow-session-columns",
        operation = DatabaseColumnMigrations::apply,
      ),
      DatabaseMigration(
        version = 2,
        name = "normalize-feedback-event-outcomes",
        operation = FeedbackEventMigration::apply,
      ),
    ).also(::requireDeterministicMigrations)

  fun apply(connection: Connection) {
    val appliedVersions = appliedMigrationVersions(connection)
    migrations
      .filterNot { migration -> migration.version in appliedVersions }
      .forEach { migration ->
        connection.inTransaction {
          migration.apply(this)
          recordMigration(migration)
        }
      }
  }

  private fun appliedMigrationVersions(connection: Connection): Set<Int> = connection.prepareStatement(
    """
      SELECT version
      FROM schema_migrations
      ORDER BY version
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getInt("version"))
        }
      }
    }
  }

  private fun Connection.recordMigration(migration: DatabaseMigration) {
    prepareStatement(
      """
      INSERT INTO schema_migrations (version, name)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setInt(1, migration.version)
      statement.setString(2, migration.name)
      statement.executeUpdate()
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

internal class DatabaseMigration(
  val version: Int,
  val name: String,
  private val operation: (Connection) -> Unit,
) {
  fun apply(connection: Connection) {
    operation(connection)
  }
}

internal inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
  val previousAutoCommit = autoCommit
  autoCommit = false
  return try {
    val result = block()
    commit()
    result
  } catch (error: SQLException) {
    rollback()
    throw error
  } catch (error: IllegalArgumentException) {
    rollback()
    throw error
  } finally {
    autoCommit = previousAutoCommit
  }
}
