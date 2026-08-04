package skillbill.db

import org.sqlite.SQLiteException
import skillbill.db.core.DatabaseRuntime
import skillbill.error.DatabaseAccessError
import skillbill.error.DatabaseAccessOperation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseAccessFailureTest {
  @Test
  fun `unopenable database on the read path raises the typed error with the resolved path`() {
    val unopenable = unopenableDatabasePath()

    val error = assertFailsWith<DatabaseAccessError> {
      DatabaseRuntime.openReadDb(cliValue = unopenable.toString(), environment = emptyMap())
    }

    assertEquals(unopenable.toAbsolutePath().normalize().toString(), error.dbPath)
    assertEquals(DatabaseAccessOperation.READ, error.operation)
    assertTrue(error.condition.startsWith("sqlite result code "), error.condition)
  }

  @Test
  fun `no sqlite exception escapes the read path`() {
    val unopenable = unopenableDatabasePath()

    val thrown = runCatching {
      DatabaseRuntime.openReadDb(cliValue = unopenable.toString(), environment = emptyMap())
    }.exceptionOrNull()

    assertFalse(thrown is SQLiteException, "raw JDBC exception escaped: $thrown")
    assertTrue(thrown is DatabaseAccessError, "expected the typed error, got $thrown")
    val rendered = thrown.message.orEmpty()
    assertFalse(rendered.contains("org.sqlite"), rendered)
    assertFalse(rendered.contains("\n"), rendered)
  }

  @Test
  fun `a failing open leaves no connection holding the database file`() {
    val tempDir = createTempDirectory("skill-bill-db-access")
    val dbPath = tempDir.resolve("review-metrics.db")
    Files.write(dbPath, "this is not a sqlite database".toByteArray())
    // A read-only open of a non-database file must not keep the file handle that would block deletion.
    runCatching { DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap()).close() }

    assertTrue(Files.deleteIfExists(dbPath), "the temp database file could not be deleted after the failed open")
  }

  @Test
  fun `a healthy database still opens on the read path`() {
    val tempDir = createTempDirectory("skill-bill-db-access-ok")
    val dbPath = tempDir.resolve("review-metrics.db")
    DatabaseRuntime.openDb(cliValue = dbPath.toString(), environment = emptyMap()).close()

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap()).use { open ->
      assertContains(open.dbPath.toString(), "review-metrics.db")
      assertFalse(open.connection.isClosed)
    }
  }

  private fun unopenableDatabasePath(): Path {
    val tempDir = createTempDirectory("skill-bill-db-unopenable")
    // A directory standing in for the database file exists, so the read path skips bootstrap and
    // fails inside SQLite's open instead.
    return tempDir.resolve("review-metrics.db").also { it.createDirectories() }
  }
}
