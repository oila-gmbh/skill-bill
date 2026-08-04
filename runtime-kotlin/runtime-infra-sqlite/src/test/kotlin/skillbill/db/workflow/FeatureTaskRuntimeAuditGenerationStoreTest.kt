package skillbill.db.workflow

import skillbill.db.core.DatabaseMigrations
import skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRow
import skillbill.tempDbConnection
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGenerationStoreTest {
  private val opened = mutableListOf<Connection>()

  @AfterTest
  fun closeConnections() {
    opened.forEach(Connection::close)
  }

  @Test
  fun `the appended migration is name-keyed after dropping the delegated review lifecycle tables`() {
    val names = DatabaseMigrations.migrations.map { it.name }
    assertEquals(
      "add-feature-task-runtime-audit-generations",
      names.last(),
      "the audit-generation migration is appended, never inserted or renumbered",
    )
    assertTrue(
      names.indexOf("drop-delegated-review-lifecycle-tables") <
        names.indexOf("add-feature-task-runtime-audit-generations"),
    )
    assertEquals(names.distinct(), names, "migration names are unique so the name-keyed rule can apply once")
  }

  @Test
  fun `re-applying migrations against an already-migrated database is idempotent`() {
    val (_, connection) = connect()
    val before = generationTableColumns(connection)

    DatabaseMigrations.apply(connection)

    assertEquals(before, generationTableColumns(connection))
  }

  @Test
  fun `appended generations read back in ordinal order`() {
    val store = store()

    store.append(row(2))
    store.append(row(1))
    store.append(row(1, workflowId = "wf-other"))

    assertEquals(listOf(1, 2), store.listOrdered(WORKFLOW).map { it.generationOrdinal })
    assertEquals(listOf(1), store.listOrdered("wf-other").map { it.generationOrdinal })
  }

  @Test
  fun `a duplicate generation ordinal for one workflow is rejected rather than overwritten`() {
    val store = store()
    store.append(row(1, checkpoint = "first"))

    assertFailsWith<SQLException> { store.append(row(1, checkpoint = "second")) }

    assertEquals(listOf("first"), store.listOrdered(WORKFLOW).map { it.repositoryCheckpoint })
  }

  @Test
  fun `the port exposes no update or delete of an existing generation outside quarantine`() {
    val members = skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRepository::class.java
      .declaredMethods
      .map { it.name }
      .toSet()

    assertEquals(setOf("append", "listOrdered", "quarantineAll"), members)
  }

  @Test
  fun `quarantine discards only the named workflow's history so it can regenerate in band`() {
    val store = store()
    store.append(row(1))
    store.append(row(2))
    store.append(row(1, workflowId = "wf-other"))

    assertEquals(2, store.quarantineAll(WORKFLOW))

    assertEquals(emptyList(), store.listOrdered(WORKFLOW))
    assertEquals(listOf(1), store.listOrdered("wf-other").map { it.generationOrdinal })

    // Regeneration is an ordinary append after quarantine: the ordinal sequence restarts at 1.
    store.append(row(1))
    assertEquals(listOf(1), store.listOrdered(WORKFLOW).map { it.generationOrdinal })
  }

  @Test
  fun `a row carrying a foreign contract version cannot become durable history`() {
    val store = store()

    assertFailsWith<SQLException> { store.append(row(1, contractVersion = "0.2")) }
  }

  private fun store(): FeatureTaskRuntimeAuditGenerationStore =
    FeatureTaskRuntimeAuditGenerationStore(connect().second)

  private fun connect(): Pair<Path, Connection> = tempDbConnection("audit-generations").also {
    opened += it.second
  }

  private fun generationTableColumns(connection: Connection): List<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info(feature_task_runtime_audit_generations)").use { rows ->
        buildList {
          while (rows.next()) add(rows.getString("name"))
        }
      }
    }

  private fun row(
    ordinal: Int,
    workflowId: String = WORKFLOW,
    checkpoint: String = "9f2c1ab",
    contractVersion: String = "0.1",
  ) = FeatureTaskRuntimeAuditGenerationRow(
    workflowId = workflowId,
    generationOrdinal = ordinal,
    repositoryCheckpoint = checkpoint,
    contractVersion = contractVersion,
    generationJson = """{"contract_version":"0.1","generation_ordinal":$ordinal}""",
  )

  private companion object {
    const val WORKFLOW = "wf-audit-generations"
  }
}
