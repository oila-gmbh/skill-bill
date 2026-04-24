package skillbill.application

import skillbill.RuntimeContext
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningRecord
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningSourceValidation
import skillbill.learnings.RejectedLearningSourceOutcome
import skillbill.learnings.UpdateLearningRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LearningResolution
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRecord
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.FeedbackRequest
import skillbill.review.FeedbackTelemetryOptions
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationPersistencePortTest {
  @Test
  fun `learning list can run with fake repositories through a read unit of work`() {
    val learningRepository =
      FakeLearningRepository(
        records =
        mutableMapOf(
          1 to learningRecord(id = 1, title = "Keep prompts stable"),
        ),
      )
    val database = FakeDatabaseSessionFactory(learnings = learningRepository)
    val service = LearningService(database)

    val result = service.list(status = "active", dbOverride = null)

    assertEquals(listOf("read"), database.calls)
    assertEquals("/fake/metrics.db", result.dbPath)
    assertEquals(listOf("Keep prompts stable"), result.learnings.map { it.title })
  }

  @Test
  fun `learning add owns a write transaction at the application boundary`() {
    val reviewRepository =
      FakeReviewRepository(
        sourceFindingExists = true,
        rejectedLearningSourceOutcome = RejectedLearningSourceOutcome("fix_rejected", "Rejected by reviewer."),
      )
    val learningRepository = FakeLearningRepository()
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository, learnings = learningRepository)
    val service = LearningService(database)

    val result =
      service.add(
        AddLearningInput(
          scope = LearningScope.SKILL,
          scopeKey = "bill-kotlin-code-review",
          title = "Prefer ports",
          rule = "Application services should depend on persistence ports.",
          reason = "Keeps use cases testable.",
          fromRun = "rvw-1",
          fromFinding = "F-1",
        ),
        dbOverride = null,
      )

    assertEquals(listOf("transaction"), database.calls)
    assertEquals("Prefer ports", result.learning.title)
    assertEquals("bill-kotlin-code-review", learningRepository.addedRequests.single().scopeKey)
    assertEquals(listOf("rvw-1:F-1"), reviewRepository.learningSourceLookups)
  }

  @Test
  fun `learning add rejects sources that repository cannot prove were rejected`() {
    val database = FakeDatabaseSessionFactory(reviews = FakeReviewRepository(sourceFindingExists = true))
    val service = LearningService(database)

    kotlin.test.assertFailsWith<IllegalArgumentException> {
      service.add(
        AddLearningInput(
          scope = LearningScope.SKILL,
          scopeKey = "bill-kotlin-code-review",
          title = "Prefer ports",
          rule = "Application services should depend on persistence ports.",
          reason = "Keeps use cases testable.",
          fromRun = "rvw-1",
          fromFinding = "F-1",
        ),
        dbOverride = null,
      )
    }
  }

  @Test
  fun `review triage records decisions inside one application transaction`() {
    val reviewRepository =
      FakeReviewRepository(
        numberedFindings =
        listOf(
          numberedFinding(1, "F-001"),
          numberedFinding(2, "F-002"),
        ),
      )
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository)
    val service =
      ReviewService(
        RuntimeContext(environment = emptyMap(), userHome = Files.createTempDirectory("skillbill-app-fake")),
        database,
        FakeTelemetrySettingsProvider(enabled = false),
      )

    val result =
      service.triage(
        runId = "rvw-1",
        decisions = listOf("all fix - patched"),
        listOnly = false,
        dbOverride = null,
      )

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(listOf("F-001", "F-002"), reviewRepository.feedbackRequests.map { it.findingIds.single() })
    assertEquals(listOf("fix_applied", "fix_applied"), result.recorded.map { it["outcome_type"] })
  }

  @Test
  fun `telemetry sync uses outbox repository and client ports inside transaction`() {
    val outboxRepository =
      InMemoryTelemetryOutboxRepository(
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
    val database = FakeDatabaseSessionFactory(telemetryOutbox = outboxRepository)
    val client = FakeTelemetryClient()
    val service =
      TelemetryService(
        database = database,
        settingsProvider = FakeTelemetrySettingsProvider(enabled = true),
        configStore = FakeTelemetryConfigStore,
        telemetryClient = client,
      )

    val result = service.sync(dbOverride = null)

    assertEquals(listOf("transaction"), database.calls)
    assertEquals("synced", result.payload["sync_status"])
    assertEquals(listOf(listOf(1L)), client.sentBatchIds)
    assertEquals(0, outboxRepository.pendingCount())
  }
}

private class FakeDatabaseSessionFactory(
  private val reviews: ReviewRepository = FakeReviewRepository(),
  private val learnings: LearningRepository = FakeLearningRepository(),
  private val telemetryOutbox: TelemetryOutboxRepository = NoopTelemetryOutboxRepository,
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
    override val dbPath: Path = this@FakeDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository = this@FakeDatabaseSessionFactory.reviews
    override val learnings: LearningRepository = this@FakeDatabaseSessionFactory.learnings
    override val telemetryOutbox: TelemetryOutboxRepository = this@FakeDatabaseSessionFactory.telemetryOutbox
    override val workflowStates: WorkflowStateRepository = NoopWorkflowStateRepository
  }
}

private class FakeLearningRepository(
  private val records: MutableMap<Int, LearningRecord> = mutableMapOf(),
) : LearningRepository {
  val addedRequests = mutableListOf<CreateLearningRequest>()

  override fun list(status: String): List<LearningRecord> =
    records.values.filter { status == "all" || it.status == status }.sortedBy { it.id }

  override fun get(id: Int): LearningRecord = records.getValue(id)

  override fun resolve(repoScopeKey: String?, skillName: String?): LearningResolution =
    LearningResolution(repoScopeKey = repoScopeKey, skillName = skillName, records = list(status = "active"))

  override fun saveSessionLearnings(reviewSessionId: String, learningsJson: String) = Unit

  override fun add(request: CreateLearningRequest, sourceValidation: LearningSourceValidation): Int {
    addedRequests += request
    val id = (records.keys.maxOrNull() ?: 0) + 1
    records[id] =
      learningRecord(id = id, title = request.title).copy(
        scope = request.scope.wireName,
        scopeKey = request.scopeKey,
        ruleText = request.ruleText,
        rationale = request.rationale,
        sourceReviewRunId = sourceValidation.reviewRunId,
        sourceFindingId = sourceValidation.findingId,
      )
    return id
  }

  override fun edit(request: UpdateLearningRequest): LearningRecord =
    records.getValue(request.learningId).let { current ->
      current.copy(
        scope = request.scope?.wireName ?: current.scope,
        scopeKey = request.scopeKey ?: current.scopeKey,
        title = request.title ?: current.title,
        ruleText = request.ruleText ?: current.ruleText,
        rationale = request.rationale ?: current.rationale,
      ).also { records[request.learningId] = it }
    }

  override fun setStatus(id: Int, status: String): LearningRecord =
    records.getValue(id).copy(status = status).also { records[id] = it }

  override fun delete(id: Int) {
    records.remove(id)
  }
}

private class FakeReviewRepository(
  private val numberedFindings: List<NumberedFinding> = emptyList(),
  private val sourceFindingExists: Boolean = false,
  private val rejectedLearningSourceOutcome: RejectedLearningSourceOutcome? = null,
) : ReviewRepository {
  val feedbackRequests = mutableListOf<FeedbackRequest>()
  val learningSourceLookups = mutableListOf<String>()

  override fun saveImportedReview(review: ImportedReview, sourcePath: String?) = error("Unexpected saveImportedReview")

  override fun markOrchestrated(runId: String) = error("Unexpected markOrchestrated")

  override fun updateReviewFinishedTelemetryState(runId: String, enabled: Boolean, level: String): Map<String, Any?>? =
    null

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
  ): Map<String, Any?>? {
    feedbackRequests += request
    return null
  }

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> = numberedFindings

  override fun findingExists(runId: String, findingId: String): Boolean {
    learningSourceLookups += "$runId:$findingId"
    return sourceFindingExists
  }

  override fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome? =
    rejectedLearningSourceOutcome

  override fun reviewStatsPayload(runId: String?): Map<String, Any?> = error("Unexpected reviewStatsPayload")

  override fun featureImplementStatsPayload(): Map<String, Any?> = error("Unexpected featureImplementStatsPayload")

  override fun featureVerifyStatsPayload(): Map<String, Any?> = error("Unexpected featureVerifyStatsPayload")
}

private object NoopTelemetryOutboxRepository : TelemetryOutboxRepository {
  override fun enqueue(eventName: String, payloadJson: String): Long = error("Unexpected enqueue")

  override fun listPending(limit: Int?): List<TelemetryOutboxRecord> = emptyList()

  override fun pendingCount(): Int = 0

  override fun latestError(): String? = null

  override fun markSynced(id: Long, syncedAt: String) = Unit

  override fun markSynced(eventIds: List<Long>) = Unit

  override fun markFailed(id: Long, lastError: String) = Unit

  override fun markFailed(eventIds: List<Long>, lastError: String) = Unit

  override fun clear(): Int = 0
}

private class InMemoryTelemetryOutboxRepository(
  private val rows: MutableList<TelemetryOutboxRecord> = mutableListOf(),
) : TelemetryOutboxRepository {
  override fun enqueue(eventName: String, payloadJson: String): Long = error("Unexpected enqueue")

  override fun listPending(limit: Int?): List<TelemetryOutboxRecord> =
    rows.filter { it.syncedAt == null }.let { pending ->
      if (limit == null) pending else pending.take(limit)
    }

  override fun pendingCount(): Int = rows.count { it.syncedAt == null }

  override fun latestError(): String? = rows.lastOrNull { it.syncedAt == null && it.lastError.isNotBlank() }?.lastError

  override fun markSynced(id: Long, syncedAt: String) {
    markSynced(listOf(id))
  }

  override fun markSynced(eventIds: List<Long>) {
    rows.replaceAll { row ->
      if (row.id in eventIds) row.copy(syncedAt = "2026-04-24 00:00:01", lastError = "") else row
    }
  }

  override fun markFailed(id: Long, lastError: String) {
    markFailed(listOf(id), lastError)
  }

  override fun markFailed(eventIds: List<Long>, lastError: String) {
    rows.replaceAll { row ->
      if (row.id in eventIds) row.copy(lastError = lastError) else row
    }
  }

  override fun clear(): Int {
    val count = rows.size
    rows.clear()
    return count
  }
}

private class FakeTelemetrySettingsProvider(
  private val enabled: Boolean,
) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = if (enabled) "anonymous" else "off",
    enabled = enabled,
    installId = if (enabled) "fake-install-id" else "",
    proxyUrl = if (enabled) "https://telemetry.example.dev/ingest" else "",
    customProxyUrl = if (enabled) "https://telemetry.example.dev/ingest" else null,
    batchSize = 50,
  )
}

private object FakeTelemetryConfigStore : TelemetryConfigStore {
  override fun stateDir(): Path = Path.of("/fake")

  override fun configPath(): Path = Path.of("/fake/config.json")

  override fun read(): Map<String, Any?>? = null

  override fun ensure(): Map<String, Any?> = emptyMap()

  override fun write(payload: Map<String, Any?>) = Unit

  override fun delete(): Boolean = true
}

private class FakeTelemetryClient : TelemetryClient {
  val sentBatchIds = mutableListOf<List<Long>>()

  override fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>) {
    sentBatchIds += rows.map { it.id }
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): Map<String, Any?> =
    error("Unexpected fetchProxyCapabilities")

  override fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): Map<String, Any?> =
    error("Unexpected fetchRemoteStats")
}

private object NoopWorkflowStateRepository : WorkflowStateRepository {
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null
}

private fun learningRecord(id: Int, title: String = "Learning $id"): LearningRecord = LearningRecord(
  id = id,
  scope = "global",
  scopeKey = "global",
  title = title,
  ruleText = "Rule $id",
  rationale = "",
  status = "active",
  sourceReviewRunId = "rvw-1",
  sourceFindingId = "F-$id",
  createdAt = "2026-04-24 00:00:00",
  updatedAt = "2026-04-24 00:00:00",
)

private fun numberedFinding(number: Int, findingId: String): NumberedFinding = NumberedFinding(
  number = number,
  findingId = findingId,
  severity = "Major",
  confidence = "High",
  location = "README.md:1",
  description = "Example finding",
)
