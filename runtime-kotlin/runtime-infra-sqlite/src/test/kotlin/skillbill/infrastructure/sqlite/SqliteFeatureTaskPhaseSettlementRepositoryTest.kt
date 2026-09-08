package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteFeatureTaskPhaseSettlementRepositoryTest {
  @Test
  fun `migration v35 creates settlement table and supports upsert find delete`() {
    val dbPath = Files.createTempDirectory("phase-settlement").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(tableExists(connection, "feature_task_phase_settlements"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 35 && row.name == "add-feature-task-phase-settlements"
        },
      )
    }
    val repo = SqliteFeatureTaskPhaseSettlementRepository()
    val dbOverride = dbPath.toString()
    val settlement = FeatureTaskPhaseSettlement(
      workflowId = "wftr-1",
      phaseId = "implement",
      attempt = 1,
      kind = FeatureTaskPhaseSettlementKind.COMPLETE,
      envelopeJson = """{"status":"completed","produced_outputs":{"value":"x"}}""",
      recordedAt = Instant.now().toString(),
    )
    repo.upsert(settlement, dbOverride)
    assertEquals(FeatureTaskPhaseSettlementKind.COMPLETE, repo.find("wftr-1", "implement", 1, dbOverride)?.kind)
    assertTrue(repo.delete("wftr-1", "implement", 1, dbOverride))
    assertNull(repo.find("wftr-1", "implement", 1, dbOverride))
    assertFalse(repo.delete("wftr-1", "implement", 1, dbOverride))
  }

  @Test
  fun `unknown settlement kind survives durable round trip`() {
    val dbPath = Files.createTempDirectory("phase-settlement-unknown").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    val repo = SqliteFeatureTaskPhaseSettlementRepository()
    val dbOverride = dbPath.toString()
    val unknown = FeatureTaskPhaseSettlementKind.Unknown("future_kind")
    repo.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = "wftr-unknown",
        phaseId = "implement",
        attempt = 1,
        kind = unknown,
        envelopeJson = "{\"status\":\"completed\"}",
        recordedAt = Instant.now().toString(),
      ),
      dbOverride,
    )

    assertEquals(unknown, repo.find("wftr-unknown", "implement", 1, dbOverride)?.kind)
  }

  private fun tableExists(connection: Connection, name: String): Boolean =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'",
      ).use { it.next() }
    }

  private data class MigrationRow(val version: Int, val name: String)

  private fun migrationRows(connection: Connection): List<MigrationRow> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT version, name FROM schema_migrations ORDER BY version").use { rows ->
        buildList {
          while (rows.next()) {
            add(MigrationRow(rows.getInt("version"), rows.getString("name")))
          }
        }
      }
    }
}
