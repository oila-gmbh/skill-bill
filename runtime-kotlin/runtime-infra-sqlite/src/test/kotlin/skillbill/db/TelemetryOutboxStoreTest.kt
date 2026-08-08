package skillbill.db

import skillbill.SkillBillVersion
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.TelemetryOutboxStore
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryOutboxStoreTest {
  @Test
  fun `telemetry outbox tracks pending rows until they are marked synced`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection)

      val firstId = store.enqueue(eventName = "skillbill_feature_implement_started", payloadJson = """{"id":"1"}""")
      val secondId = store.enqueue(eventName = "skillbill_feature_verify_started", payloadJson = """{"id":"2"}""")

      assertEquals(2, store.pendingCount())
      assertEquals(listOf(firstId, secondId), store.listPending().map { it.id })

      store.markFailed(id = firstId, lastError = "connection refused")
      assertEquals("connection refused", store.listPending().first().lastError)

      store.markSynced(id = firstId, syncedAt = "2026-04-23 00:00:00")

      val pendingRows = store.listPending()
      assertEquals(1, store.pendingCount())
      assertEquals(listOf(secondId), pendingRows.map { it.id })
      assertTrue(pendingRows.all { it.syncedAt == null })

      assertEquals(2, store.clear())
      assertEquals(0, store.pendingCount())
    }
  }

  // SKILL-136 subtask 6 AC-001: the success path clears the error signal to SQL NULL, not to ''.
  // Writing '' here is what made 10,495 healthy rows indistinguishable from failed ones.
  @Test
  fun `marking an event synced clears last_error to SQL NULL`() {
    withOutbox { connection, store ->
      val id = store.enqueue(eventName = "skillbill_review_finished", payloadJson = "{}")
      store.markFailed(id = id, lastError = "connection refused")
      assertEquals("connection refused", store.latestError())

      store.markSynced(id = id, syncedAt = "2026-04-23 00:00:00")

      assertEquals(
        0,
        countWhere(connection, "last_error IS NOT NULL"),
        "A delivered event must leave no error signal behind at all.",
      )
      assertEquals(null, store.latestError(), "A drained outbox must report no delivery error.")
    }
  }

  @Test
  fun `latestError reports a real failure while healthy rows stay NULL`() {
    withOutbox { connection, store ->
      val healthy = store.enqueue(eventName = "skillbill_goal_finished", payloadJson = "{}")
      val failed = store.enqueue(eventName = "skillbill_review_finished", payloadJson = "{}")
      store.markFailed(id = failed, lastError = "boom")

      assertEquals("boom", store.latestError())
      assertEquals(2, store.pendingCount(), "Recording a failure must not retire either pending row.")
      assertEquals(
        1,
        countWhere(connection, "id = $healthy AND last_error IS NULL"),
        "The untouched row must stay NULL rather than being reclassified as failed.",
      )
      assertEquals("", store.listPending().single { it.id == healthy }.lastError)
    }
  }

  // A store still carrying the pre-migration '' convention must not have those rows read as errors.
  @Test
  fun `latestError ignores legacy empty-string rows`() {
    withOutbox { connection, store ->
      store.enqueue(eventName = "skillbill_goal_finished", payloadJson = "{}")
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE telemetry_outbox SET last_error = ''")
      }

      assertEquals(null, store.latestError(), "A legacy empty-string row is healthy, not a delivery failure.")
    }
  }

  // AC-009: the outbox still drains fully with the nullable column in place.
  @Test
  fun `the outbox drains fully after every row is marked synced`() {
    withOutbox { _, store ->
      val ids =
        List(3) { index -> store.enqueue(eventName = "skillbill_goal_finished", payloadJson = """{"i":$index}""") }
      store.markFailed(eventIds = ids, lastError = "transient")

      store.markSynced(ids)

      assertTrue(store.listPending().isEmpty(), "Every synced row must leave the pending set.")
      assertEquals(0, store.pendingCount())
      assertEquals(null, store.latestError(), "A fully drained outbox reports no error.")
    }
  }

  // SKILL-163 AC-001/AC-003: the version is recorded at insert time from the store's own value, so
  // no payload-builder call site has to pass it.
  @Test
  fun `enqueue records the running skill-bill version without the caller supplying it`() {
    withOutbox { connection, store ->
      val id = store.enqueue(eventName = "skillbill_goal_finished", payloadJson = "{}")

      assertEquals(
        SkillBillVersion.VALUE,
        scalarString(connection, "SELECT skill_bill_version FROM telemetry_outbox WHERE id = $id"),
      )
    }
  }

  @Test
  fun `enqueue records the store's injected version and listPending surfaces it`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox-version").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = TelemetryOutboxStore(connection, version = "9.9.9-injected")

      val id = store.enqueue(eventName = "skillbill_goal_finished", payloadJson = "{}")

      assertEquals(
        "9.9.9-injected",
        scalarString(connection, "SELECT skill_bill_version FROM telemetry_outbox WHERE id = $id"),
      )
      assertEquals("9.9.9-injected", store.listPending().single { it.id == id }.skillBillVersion)
    }
  }

  // SKILL-170 AC-002: the status surface needs a never-synced/synced distinction that survives a non-empty outbox.
  @Test
  fun `lastSyncedAt is null until a row is marked synced`() {
    withOutbox { _, store ->
      val id = store.enqueue(eventName = "skillbill_goal_finished", payloadJson = "{}")
      store.enqueue(eventName = "skillbill_review_finished", payloadJson = "{}")

      assertEquals(null, store.lastSyncedAt(), "An outbox that never delivered has no successful sync timestamp.")

      store.markSynced(listOf(id))

      assertNotNull(store.lastSyncedAt(), "A delivered row must expose a successful sync timestamp.")
      assertEquals(1, store.pendingCount(), "The still-pending row must remain queued alongside the sync timestamp.")
    }
  }

  private fun scalarString(connection: Connection, sql: String): String? = connection.createStatement().use { st ->
    st.executeQuery(sql).use { rows ->
      check(rows.next())
      rows.getString(1)
    }
  }

  private fun withOutbox(block: (Connection, TelemetryOutboxStore) -> Unit) {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-outbox").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      block(connection, TelemetryOutboxStore(connection))
    }
  }

  private fun countWhere(connection: Connection, predicate: String): Int = connection.createStatement().use { st ->
    st.executeQuery("SELECT COUNT(*) FROM telemetry_outbox WHERE $predicate").use { rows ->
      check(rows.next())
      rows.getInt(1)
    }
  }
}
