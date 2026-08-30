package skillbill.application

import skillbill.application.learning.LearningService
import skillbill.application.learning.model.AddLearningInput
import skillbill.application.review.ReviewService
import skillbill.application.telemetry.RUNTIME_EXCEPTION_EVENT
import skillbill.application.telemetry.TelemetryService
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.model.EnvironmentContext
import skillbill.ports.review.EmptyReviewAttributionPort
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    assertFailsWith<IllegalArgumentException> {
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
        EnvironmentContext(environment = emptyMap(), userHome = Files.createTempDirectory("skillbill-app-fake")),
        database,
        FakeTelemetrySettingsProvider(enabled = false),
        FakeReviewInputSource,
        EmptyReviewAttributionPort,
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
    assertEquals(listOf("fix_applied", "fix_applied"), result.recorded.map { it.outcomeType })
  }

  // SKILL-136 subtask 5 AC-001/AC-002: lane identity comes from the composed launch plan. Narration
  // that disagrees with the plan is retained as an unresolved lane, never used as identity. The
  // routed pack slug resolves from the canonical skill name, so this holds in a consumer repository
  // where no platform-packs directory exists and routedSkillPlatformSlugs() is empty.
  @Test
  fun `review import records lanes from the composed plan rather than the narration string`() {
    val reviewRepository = FakeReviewRepository()
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository)

    laneReviewService(database, reviewText(findings = true)).importReview(input = "-", dbOverride = null)

    val lanes = reviewRepository.savedReviews.single().planLanes
    assertEquals(
      listOf("bill-kmp-code-review-architecture", "narrated-only"),
      lanes.map { it.laneSkillName },
    )
    assertEquals("kmp", lanes.first().packSlug)
    assertEquals("architecture", lanes.first().area)
    assertEquals("resolved", lanes.first().resolutionState)
    assertEquals("unresolved", lanes.last().resolutionState)
  }

  // AC-002/AC-005/AC-006: a run that produced no findings still records its lanes and its terminal
  // facts, and does so even though telemetry is disabled for this session.
  @Test
  fun `a zero findings import still records lanes and terminal review state`() {
    val reviewRepository = FakeReviewRepository()
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository)

    laneReviewService(database, reviewText(findings = false)).importReview(input = "-", dbOverride = null)

    val saved = reviewRepository.savedReviews.single()
    assertEquals(emptyList(), saved.findings)
    assertEquals(listOf("bill-kmp-code-review-architecture", "narrated-only"), saved.planLanes.map { it.laneSkillName })
    assertEquals(listOf<Pair<String, String?>>("rvw-lane-app-001" to "inline"), reviewRepository.terminalStateWrites)
  }

  @Test
  fun `a run whose routed pack cannot be resolved still imports with unresolved lanes`() {
    val reviewRepository = FakeReviewRepository()
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository)
    val service = ReviewService(
      EnvironmentContext(
        environment = emptyMap(),
        userHome = Files.createTempDirectory("skillbill-app-lane-unrouted"),
        stdinText = reviewText(findings = false),
      ),
      database,
      FakeTelemetrySettingsProvider(enabled = false),
      FakeReviewInputSource,
      EmptyReviewAttributionPort,
    )

    service.importReview(input = "-", dbOverride = null)

    val lanes = reviewRepository.savedReviews.single().planLanes
    assertEquals(listOf("architecture", "narrated-only"), lanes.map { it.laneSkillName })
    assertTrue(lanes.all { it.resolutionState == "unresolved" })
    assertEquals(listOf<Pair<String, String?>>("rvw-lane-app-001" to "inline"), reviewRepository.terminalStateWrites)
  }

  // A partially staged catalog — the routed pack composes a baseline layer that is not installed —
  // makes composition throw. Attribution is best-effort: the import must still land the run.
  @Test
  fun `a composition failure degrades to unresolved lanes rather than failing the import`() {
    val reviewRepository = FakeReviewRepository()
    val database = FakeDatabaseSessionFactory(reviews = reviewRepository)
    val service = ReviewService(
      EnvironmentContext(
        environment = emptyMap(),
        userHome = Files.createTempDirectory("skillbill-app-lane-broken"),
        stdinText = reviewText(findings = false),
      ),
      database,
      FakeTelemetrySettingsProvider(enabled = false),
      FakeReviewInputSource,
      ThrowingPlanReviewAttributionPort,
    )

    service.importReview(input = "-", dbOverride = null)

    val lanes = reviewRepository.savedReviews.single().planLanes
    assertEquals(listOf("architecture", "narrated-only"), lanes.map { it.laneSkillName })
    assertTrue(lanes.all { it.resolutionState == "unresolved" })
    assertEquals(listOf<Pair<String, String?>>("rvw-lane-app-001" to "inline"), reviewRepository.terminalStateWrites)
  }

  @Test
  fun `manual telemetry sync reconciles before using short outbox sessions`() {
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
    val reconciliationRepository = RecordingTelemetryReconciliationRepository()
    val database = FakeDatabaseSessionFactory(
      telemetryOutbox = outboxRepository,
      telemetryReconciliation = reconciliationRepository,
    )
    val client = FakeTelemetryClient()
    val service =
      TelemetryService(
        database = database,
        settingsProvider = FakeTelemetrySettingsProvider(enabled = true),
        configStore = FakeTelemetryConfigStore,
        telemetryClient = client,
      )

    val result = service.sync(dbOverride = null)

    assertEquals(listOf("transaction", "read", "read", "transaction", "read", "read"), database.calls)
    assertEquals(listOf("anonymous"), reconciliationRepository.levels)
    assertEquals("synced", result.result.syncStatus)
    assertEquals(listOf(listOf(1L)), client.sentBatchIds)
    assertEquals(0, outboxRepository.pendingCount())
  }

  @Test
  fun `telemetry auto sync reconciles stale sessions before listing pending outbox events`() {
    val outboxRepository =
      InMemoryTelemetryOutboxRepository(
        mutableListOf(
          TelemetryOutboxRecord(
            id = 1,
            eventName = "skillbill_feature_verify_finished",
            payloadJson = """{"name":"ok"}""",
            createdAt = "2026-04-24 00:00:00",
            syncedAt = null,
            lastError = "",
          ),
        ),
      )
    val reconciliationRepository = RecordingTelemetryReconciliationRepository()
    val database = FakeDatabaseSessionFactory(
      telemetryOutbox = outboxRepository,
      telemetryReconciliation = reconciliationRepository,
    )
    val client = FakeTelemetryClient()
    val service =
      TelemetryService(
        database = database,
        settingsProvider = FakeTelemetrySettingsProvider(enabled = true),
        configStore = FakeTelemetryConfigStore,
        telemetryClient = client,
      )

    service.autoSync(dbOverride = null)

    assertEquals("transaction", database.calls.first())
    assertEquals(listOf("anonymous"), reconciliationRepository.levels)
    assertEquals(listOf(listOf(1L)), client.sentBatchIds)
  }

  @Test
  fun `telemetry auto sync keeps syncing when stale reconciliation fails`() {
    val outboxRepository =
      InMemoryTelemetryOutboxRepository(
        mutableListOf(
          TelemetryOutboxRecord(
            id = 1,
            eventName = "skillbill_feature_verify_finished",
            payloadJson = """{"name":"ok"}""",
            createdAt = "2026-04-24 00:00:00",
            syncedAt = null,
            lastError = "",
          ),
        ),
      )
    val database = FakeDatabaseSessionFactory(
      telemetryOutbox = outboxRepository,
      telemetryReconciliation = ThrowingTelemetryReconciliationRepository,
    )
    val client = FakeTelemetryClient()
    val service =
      TelemetryService(
        database = database,
        settingsProvider = FakeTelemetrySettingsProvider(enabled = true),
        configStore = FakeTelemetryConfigStore,
        telemetryClient = client,
      )

    service.autoSync(dbOverride = null)

    assertEquals("transaction", database.calls.first())
    assertEquals(listOf(RUNTIME_EXCEPTION_EVENT), outboxRepository.enqueuedEventNames)
    assertEquals(listOf(1L, 2L), client.sentBatchIds.flatten())
  }

  @Test
  fun `manual sync forces reconciliation each flush while auto sync keeps the periodic cadence guard`() {
    val manualReconciliation = RecordingTelemetryReconciliationRepository()
    telemetrySyncService(manualReconciliation).run {
      sync(dbOverride = null)
      sync(dbOverride = null)
    }

    val autoReconciliation = RecordingTelemetryReconciliationRepository()
    telemetrySyncService(autoReconciliation).autoSync(dbOverride = null)

    assertEquals(listOf(0L, 0L), manualReconciliation.cadenceSeconds)
    assertEquals(listOf(100, 100), manualReconciliation.requests.map { it.maximumBatchSize })
    assertEquals(listOf(300L), autoReconciliation.cadenceSeconds)
  }
}
