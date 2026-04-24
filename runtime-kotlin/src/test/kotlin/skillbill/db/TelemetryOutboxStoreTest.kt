package skillbill.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    }
  }
}
