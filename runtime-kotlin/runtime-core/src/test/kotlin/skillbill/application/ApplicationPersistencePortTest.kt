package skillbill.application

import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.goalrunner.toRecord
import skillbill.application.learning.LearningService
import skillbill.application.learning.model.AddLearningInput
import skillbill.application.review.ReviewService
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.telemetry.RUNTIME_EXCEPTION_EVENT
import skillbill.application.telemetry.TelemetryService
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.application.telemetry.toRecord
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.openFeatureTask
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.MissingCompositionLayerError
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.learning.model.LearningResolution
import skillbill.ports.review.EmptyReviewAttributionPort
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewRepository
import skillbill.ports.review.ReviewRunCompletenessRepository
import skillbill.ports.review.UnavailableReviewRunCompletenessRepository
import skillbill.ports.review.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStatsRepository
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.GoalBlockedSubtaskSummary
import skillbill.review.model.GoalRunSummary
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
