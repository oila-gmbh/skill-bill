package skillbill.db

import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DbConstants
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabasePathsTest {
  @Test
  fun `cli path overrides environment and default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = "./custom/metrics.db",
        environment = mapOf(DbConstants.DB_ENVIRONMENT_KEY to "/tmp/env-metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("./custom/metrics.db").toAbsolutePath().normalize(), resolved)
  }

  @Test
  fun `environment path overrides default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = mapOf(DbConstants.DB_ENVIRONMENT_KEY to "~/metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/metrics.db"), resolved)
  }

  @Test
  fun `default path resolves under skill bill home`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = emptyMap(),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/.skill-bill/review-metrics.db"), resolved)
  }

  /**
   * SKILL-136 subtask 6 AC-005. This is the exact shape that produced a zero-byte
   * `<workingDir>/.skill-bill/review-metrics.db` and made a later reading conclude the store was
   * empty: the file existed, so `openReadDb`'s bootstrap branch did not fire, and it was opened
   * read-only with no schema at all. A database file must be absent or schema-complete, never both
   * present and schema-less.
   */
  @Test
  fun `openReadDb migrates a zero-byte working-directory database instead of reporting it empty`() {
    val workingDir = Files.createTempDirectory("runtime-kotlin-zero-byte-store")
    val dbPath = workingDir.resolve(".skill-bill").resolve("review-metrics.db")
    Files.createDirectories(dbPath.parent)
    Files.createFile(dbPath)
    assertEquals(0, Files.size(dbPath), "The regression starts from a genuinely zero-byte file.")

    listOf(
      DatabaseRuntime.resolveDbPath(cliValue = dbPath.toString(), environment = emptyMap()),
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = mapOf(DbConstants.DB_ENVIRONMENT_KEY to dbPath.toString()),
      ),
    ).forEach { resolved ->
      assertEquals(dbPath.toAbsolutePath().normalize(), resolved)
      DatabaseRuntime.openReadDb(cliValue = resolved.toString(), environment = emptyMap()).use { open ->
        assertTrue(
          tableNames(open.connection).containsAll(setOf("review_runs", "findings", "telemetry_outbox")),
          "A schema-less file must be migrated to schema-complete, not reported as an empty store.",
        )
      }
    }
  }

  // The pre-existing deliberate exception must keep working: an absent path still bootstraps.
  @Test
  fun `openReadDb on an absent path still bootstraps a schema-complete database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-absent-store").resolve("review-metrics.db")

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap()).use { open ->
      assertTrue(tableNames(open.connection).containsAll(setOf("review_runs", "findings")))
    }
  }

  // A store that is already schema-complete keeps read-only semantics; only the schema-less case
  // escalates to the migrating open.
  @Test
  fun `openReadDb keeps a schema-complete database read-only`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-readonly-store").resolve("review-metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap()).use { open ->
      assertFailsWith<SQLException>("An already-complete store must not gain write capability.") {
        open.connection.createStatement().use { statement ->
          statement.executeUpdate("INSERT INTO review_runs (review_run_id, routed_skill) VALUES ('rvw-x', 's')")
        }
      }
    }
  }

  private fun tableNames(connection: Connection): Set<String> = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
      buildSet { while (rows.next()) add(rows.getString("name")) }
    }
  }
}
