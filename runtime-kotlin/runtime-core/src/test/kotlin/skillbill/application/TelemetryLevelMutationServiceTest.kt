package skillbill.application

import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.EmptyWorkListRepository
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryLevelMutationServiceTest {
  @Test
  fun `clears disabled outbox inside application transaction`() {
    val outboxRepository =
      MutationTelemetryOutboxRepository(
        mutableListOf(
          TelemetryOutboxRecord(
            id = 1,
            eventName = "skillbill_feature_implement_started",
            payloadJson = """{"name":"ok"}""",
            createdAt = "2026-04-24 00:00:00",
            syncedAt = null,
            lastError = "",
          ),
        ),
      )
    val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
    val service = TelemetryLevelMutationService(
      database = database,
      settingsProvider = DisabledMutationTelemetrySettingsProvider,
      configStore = FakeMutationTelemetryConfigStore,
    )

    val result = service.setLevel("off", dbOverride = null)

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(1, result.clearedEvents)
    assertEquals(0, outboxRepository.pendingCount())
  }
}

private class FakeTelemetryDatabaseSessionFactory(
  private val telemetryOutbox: TelemetryOutboxRepository,
) : DatabaseSessionFactory {
  val calls = mutableListOf<String>()
  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T {
    calls += "read"
    return block(fakeUnitOfWork())
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    calls += "transaction"
    return block(fakeUnitOfWork())
  }

  private fun fakeUnitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@FakeTelemetryDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository
      get() = error("Unexpected reviews")
    override val learnings: LearningRepository
      get() = error("Unexpected learnings")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("Unexpected lifecycleTelemetry")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("Unexpected telemetryReconciliation")
    override val telemetryOutbox: TelemetryOutboxRepository = this@FakeTelemetryDatabaseSessionFactory.telemetryOutbox
    override val workflowStates: WorkflowStateRepository
      get() = error("Unexpected workflowStates")
    override val workList = EmptyWorkListRepository
  }
}

private class MutationTelemetryOutboxRepository(
  private val rows: MutableList<TelemetryOutboxRecord>,
) : TelemetryOutboxRepository {
  override fun enqueue(eventName: String, payloadJson: String): Long = error("Unexpected enqueue")

  override fun listPending(limit: Int?): List<TelemetryOutboxRecord> = rows

  override fun pendingCount(): Int = rows.size

  override fun latestError(): String? = null

  override fun markSynced(id: Long, syncedAt: String) = Unit

  override fun markSynced(eventIds: List<Long>) = Unit

  override fun markFailed(id: Long, lastError: String) = Unit

  override fun markFailed(eventIds: List<Long>, lastError: String) = Unit

  override fun clear(): Int {
    val count = rows.size
    rows.clear()
    return count
  }
}

private object DisabledMutationTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = "off",
    enabled = false,
    installId = "",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
}

private object FakeMutationTelemetryConfigStore : TelemetryConfigStore {
  override fun stateDir(): Path = Path.of("/fake")

  override fun configPath(): Path = Path.of("/fake/config.json")

  override fun read(): TelemetryConfigDocument? = null

  override fun ensure(): TelemetryConfigDocument = TelemetryConfigDocument(emptyMap())

  override fun write(document: TelemetryConfigDocument) = Unit

  override fun delete(): Boolean = true
}
