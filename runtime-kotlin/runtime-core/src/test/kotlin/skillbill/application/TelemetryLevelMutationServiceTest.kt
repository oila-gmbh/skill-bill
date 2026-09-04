package skillbill.application

import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.config.TelemetryConfigMutations
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    val configStore = FakeMutationTelemetryConfigStore()
    val service = TelemetryLevelMutationService(
      database = database,
      settingsProvider = DisabledMutationTelemetrySettingsProvider,
      configStore = configStore,
    )

    val result = service.setLevel("off", dbOverride = null)

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(1, result.clearedEvents)
    assertEquals(0, outboxRepository.pendingCount())
    assertEquals("off", (configStore.document.payload["telemetry"] as Map<*, *>)["level"])
    assertEquals("existing", configStore.document.payload["install_id"])
  }

  @Test
  fun `downgrade from full to anonymous clears the outbox inside a transaction`() {
    val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1), outboxRecord(2)))
    val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
    val service = TelemetryLevelMutationService(
      database = database,
      settingsProvider = LeveledMutationTelemetrySettingsProvider("full"),
      configStore = FakeMutationTelemetryConfigStore(),
    )

    val result = service.setLevel("anonymous", dbOverride = null)

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(2, result.clearedEvents, "full-level payloads must not survive a downgrade")
    assertEquals(0, outboxRepository.pendingCount())
  }

  @Test
  fun `upgrade and same level writes leave the outbox untouched`() {
    for ((current, next) in listOf("anonymous" to "full", "full" to "full")) {
      val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1)))
      val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
      val service = TelemetryLevelMutationService(
        database = database,
        settingsProvider = LeveledMutationTelemetrySettingsProvider(current),
        configStore = FakeMutationTelemetryConfigStore(),
      )

      val result = service.setLevel(next, dbOverride = null)

      assertEquals(emptyList<String>(), database.calls, "$current to $next must not open a clearing transaction")
      assertEquals(0, result.clearedEvents)
      assertEquals(1, outboxRepository.pendingCount())
    }
  }

  @Test
  fun `an unrecognized current level fails closed and clears the outbox`() {
    val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1)))
    val service = TelemetryLevelMutationService(
      database = FakeTelemetryDatabaseSessionFactory(outboxRepository),
      settingsProvider = LeveledMutationTelemetrySettingsProvider("bogus"),
      configStore = FakeMutationTelemetryConfigStore(),
    )

    assertEquals(1, service.setLevel("anonymous", dbOverride = null).clearedEvents)
    assertEquals(0, outboxRepository.pendingCount())
  }

  @Test
  fun `disable rewrites the config file in place instead of deleting it`() {
    val fixture = telemetryStoreFixture(
      seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
    )

    val (settings, cleared) = TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )

    assertTrue(Files.exists(fixture.configPath), "disable must leave config.json on disk")
    assertContains(Files.readString(fixture.configPath), "\"level\":\"off\"")
    assertEquals("off", settings.level)
    assertFalse(settings.enabled)
    assertEquals(0, cleared)
  }

  @Test
  fun `off to anonymous round trip reuses the retained install_id`() {
    val fixture = telemetryStoreFixture(
      seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
    )

    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )
    assertContains(Files.readString(fixture.configPath), "\"install_id\":\"seeded-id\"")

    val (settings, _) = TelemetryConfigMutations.setTelemetryLevel(
      level = "anonymous",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )

    assertEquals("seeded-id", settings.installId, "re-enable must not mint a fresh install id")
    assertTrue(settings.enabled)
  }

  @Test
  fun `disable preserves external addon sources and execution matrix`() {
    val fixture = telemetryStoreFixture(
      seed = """
      |{"install_id":"seeded-id","external_addon_sources":["/tmp/addons","/tmp/more"],
      |"execution_matrix":{"default":"claude","review":"codex"},
      |"telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}
      """.trimMargin(),
    )

    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )

    val payload = fixture.configStore.read()?.payload.orEmpty()
    assertEquals(listOf("/tmp/addons", "/tmp/more"), payload["external_addon_sources"])
    assertEquals(mapOf("default" to "claude", "review" to "codex"), payload["execution_matrix"])
  }

  @Test
  fun `disable clears queued outbox events and reports the cleared count`() {
    val fixture = telemetryStoreFixture(
      seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
    )
    val outbox = MutationTelemetryOutboxRepository(
      mutableListOf(
        outboxRecord(1),
        outboxRecord(2),
      ),
    )

    val (_, cleared) = TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
      outbox = outbox,
    )

    assertEquals(2, cleared)
    assertEquals(0, outbox.pendingCount())
  }

  @Test
  fun `no events are queued while the level is off`() {
    val fixture = telemetryStoreFixture(
      seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
    )
    val outbox = MutationTelemetryOutboxRepository(mutableListOf())
    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
      outbox = outbox,
    )

    // The production emit gate is LifecycleTelemetryService: driving a real lifecycle emit after
    // the disable is what proves nothing is queued. The fake unit of work errors on any
    // lifecycleTelemetry access, so removing that gate fails this test instead of leaving it green.
    val service = LifecycleTelemetryService(
      database = FakeTelemetryDatabaseSessionFactory(outbox),
      settingsProvider = fixture.settingsProvider,
    )

    val result = service.featureTaskRuntimeStarted(
      FeatureTaskRuntimeStartedRequest(featureSize = "MEDIUM", issueKey = "SKILL-163", featureName = "telemetry"),
    )

    assertEquals("skipped", result["status"], "level off must skip lifecycle emission")
    assertEquals(0, outbox.pendingCount(), "nothing may be queued while telemetry is off")
  }
}

private class TelemetryStoreFixture(
  val configPath: Path,
  val configStore: TelemetryConfigStore,
  val settingsProvider: TelemetrySettingsProvider,
)

private fun telemetryStoreFixture(seed: String): TelemetryStoreFixture {
  val home = Files.createTempDirectory("skill-bill-telemetry-disable")
  home.toFile().deleteOnExit()
  val configPath = home.resolve(".config").resolve("skill-bill").resolve("config.json")
  Files.createDirectories(configPath.parent)
  Files.writeString(configPath, seed)
  val context = EnvironmentContext(
    environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
    userHome = home,
  )
  val configStore = FileTelemetryConfigStore(context)
  return TelemetryStoreFixture(
    configPath = configPath,
    configStore = configStore,
    settingsProvider = DefaultTelemetrySettingsProvider(context, configStore),
  )
}

private fun outboxRecord(id: Long): TelemetryOutboxRecord = TelemetryOutboxRecord(
  id = id,
  eventName = "skillbill_feature_implement_started",
  payloadJson = """{"name":"ok"}""",
  createdAt = "2026-04-24 00:00:00",
  syncedAt = null,
  lastError = "",
)

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

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

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
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
    override val goalRunnerControls = EmptyGoalRunnerControlRepository
  }
}

private class MutationTelemetryOutboxRepository(
  private val rows: MutableList<TelemetryOutboxRecord>,
) : TelemetryOutboxRepository {
  override fun enqueue(eventName: String, payloadJson: String): Long {
    val id = (rows.maxOfOrNull(TelemetryOutboxRecord::id) ?: 0L) + 1
    rows +=
      TelemetryOutboxRecord(
        id = id,
        eventName = eventName,
        payloadJson = payloadJson,
        createdAt = "2026-04-24 00:00:00",
        syncedAt = null,
        lastError = "",
      )
    return id
  }

  override fun listPending(limit: Int?): List<TelemetryOutboxRecord> = rows

  override fun pendingCount(): Int = rows.size

  override fun latestError(): String? = null

  override fun lastSyncedAt(): String? = rows.mapNotNull(TelemetryOutboxRecord::syncedAt).maxOrNull()

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

private class LeveledMutationTelemetrySettingsProvider(private val level: String) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = level,
    enabled = level != "off",
    installId = "existing",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
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

private class FakeMutationTelemetryConfigStore : TelemetryConfigStore {
  var document: TelemetryConfigDocument = TelemetryConfigDocument(
    mapOf(
      "install_id" to "existing",
      "telemetry" to mapOf("level" to "anonymous", "proxy_url" to "", "batch_size" to 50),
    ),
  )

  override fun stateDir(): Path = Path.of("/fake")

  override fun configPath(): Path = Path.of("/fake/config.json")

  override fun read(): TelemetryConfigDocument = document

  override fun ensure(): TelemetryConfigDocument = document

  override fun write(document: TelemetryConfigDocument) {
    this.document = document
  }
}
