package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteException
import skillbill.error.DatabaseAccessError
import skillbill.error.DatabaseAccessOperation
import skillbill.model.EnvironmentContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteDatabaseSessionFactoryTypedFailureTest {
  @Test
  fun `read surfaces the typed error rather than a jdbc exception at the ports boundary`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val unopenable = unopenableDatabasePath(tempDir)

    val error = assertFailsWith<DatabaseAccessError> {
      database.read(unopenable.toString()) { it.workflowStates }
    }

    assertEquals(unopenable.toAbsolutePath().normalize().toString(), error.dbPath)
    assertEquals(DatabaseAccessOperation.READ, error.operation)
    assertFalse(error.message.orEmpty().contains("org.sqlite"), error.message.orEmpty())
  }

  @Test
  fun `a sqlite condition raised while executing the read converts to the typed error`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read-statement")
    val schemaless = tempDir.resolve("schemaless.db")
    DriverManager.getConnection("jdbc:sqlite:$schemaless").use { connection ->
      connection.createStatement().use { it.execute("CREATE TABLE placeholder (id INTEGER PRIMARY KEY)") }
    }
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    val error = assertFailsWith<DatabaseAccessError> {
      database.read(schemaless.toString()) { it.workflowStates.getFeatureTaskExecutionIdentity("missing") }
    }

    assertEquals(DatabaseAccessOperation.READ, error.operation)
    assertEquals(schemaless.toAbsolutePath().normalize().toString(), error.dbPath)
    assertFalse(error.message.orEmpty().contains("org.sqlite"), error.message.orEmpty())
    assertFalse(error.message.orEmpty().contains("\n"), error.message.orEmpty())
  }

  @Test
  fun `a non-sqlite failure raised inside the read block propagates unchanged`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read-passthrough")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    database.transaction(dbPath.toString()) { }

    assertFailsWith<IllegalStateException> {
      database.read(dbPath.toString()) { error("unrelated failure") }
    }
  }

  @Test
  fun `transaction surfaces the typed error rather than a jdbc exception at the ports boundary`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-transaction")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val unopenable = unopenableDatabasePath(tempDir)

    val thrown = runCatching {
      database.transaction(unopenable.toString()) { it.workflowStates }
    }.exceptionOrNull()

    assertFalse(thrown is SQLiteException, "raw JDBC exception crossed the ports boundary: $thrown")
    assertTrue(thrown is DatabaseAccessError, "expected the typed error, got $thrown")
  }

  @Test
  fun `a failure raised inside a transaction block still rolls back and propagates`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-rollback")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    assertFailsWith<IllegalStateException> {
      database.transaction(dbPath.toString()) { error("force rollback") }
    }

    // A rolled-back transaction leaves the database usable, proving ROLLBACK ran rather than a stuck BEGIN.
    database.transaction(dbPath.toString()) { }
  }

  private fun unopenableDatabasePath(tempDir: Path): Path =
    tempDir.resolve("unopenable.db").also { it.createDirectories() }
}
