package skillbill.application

import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.toRecord
import skillbill.application.learning.LearningService
import skillbill.application.model.AddLearningInput
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalStatsResult
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowLatestResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowResumeResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.application.review.ReviewService
import skillbill.application.telemetry.RUNTIME_EXCEPTION_EVENT
import skillbill.application.telemetry.TelemetryService
import skillbill.application.telemetry.toRecord
import skillbill.application.workflow.WorkflowService
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.WorkflowStatsRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.LearningResolution
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.persistence.model.TelemetryReconciliationResult
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.review.EmptyReviewAttributionPort
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.review.model.FeatureImplementWorkflowStats
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
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
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
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass") // integration suite spanning learning/review/telemetry/workflow ports
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

  @Test
  fun `telemetry sync uses short application sessions around outbox repository ports`() {
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

    assertEquals(listOf("read", "read", "transaction", "read", "read"), database.calls)
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
  fun `workflow service owns implement rows list resume and continuation through ports`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val updated = service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1)),
        artifactsPatch = mapOf("preplan_digest" to mapOf("ok" to true)),
      ),
      dbOverride = null,
    ) as WorkflowUpdateResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_PROSE, dbOverride = null)
    val latest = service.latest(WorkflowFamilyKind.TASK_PROSE, dbOverride = null) as WorkflowLatestResult.Ok
    val resumed =
      service.resume(WorkflowFamilyKind.TASK_PROSE, workflowId, dbOverride = null) as WorkflowResumeResult.Ok
    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    assertEquals(listOf("transaction", "transaction", "read", "read", "read", "transaction"), database.calls)
    assertEquals("blocked", updated.acknowledgement.workflowStatus)
    assertEquals(1, listed.workflowCount)
    assertEquals(workflowId, latest.summary.workflowId)
    assertEquals(listOf("plan"), resumed.resume.missingArtifacts)
    assertEquals("blocked", continued.view.continueStatus)
  }

  @Test
  fun `workflow service owns task runtime rows through ports for save load list latest`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val first = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val second = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-002", dbOverride = null)
      as WorkflowOpenResult.Ok

    val got = service.get(WorkflowFamilyKind.TASK_RUNTIME, first.workflowId, dbOverride = null)
      as WorkflowGetResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null)
    val latest = service.latest(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null) as WorkflowLatestResult.Ok

    assertEquals("bill-feature-task", got.snapshot.workflowName)
    assertEquals("runtime", got.snapshot.mode)
    assertEquals(2, listed.workflowCount)
    assertEquals(second.workflowId, latest.summary.workflowId)
    assertEquals(0, service.list(WorkflowFamilyKind.TASK_PROSE, dbOverride = null).workflowCount)
    assertEquals(0, service.list(WorkflowFamilyKind.VERIFY, dbOverride = null).workflowCount)
  }

  @Test
  fun `task runtime recorder mints timestamps and persists per-phase record and append-only ledger`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.START))
    assertTrue(recorder.recordPlanPhase(workflowId, status = "running", finished = false))
    assertTrue(
      recorder.recordPlanPhase(
        workflowId,
        status = "completed",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
      ),
    )
    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.COMPLETE))

    val artifacts = decodeArtifactsForTest(
      requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )

    @Suppress("UNCHECKED_CAST")
    val phaseRecords = artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val planRecord = phaseRecords["plan"] as Map<String, Any?>
    assertEquals("completed", planRecord["status"])
    assertEquals("agent-plan-1", planRecord["resolved_agent_id"])
    // Timestamps and duration are minted by the runtime, never agent-reported.
    assertTrue((planRecord["started_at"] as String).isNotBlank())
    assertTrue((planRecord["finished_at"] as String).isNotBlank())
    assertTrue((planRecord["duration_millis"] as Number).toLong() >= 0)
    assertEquals("""{"contract_version":"0.1"}""", planRecord["output_artifact"])

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(listOf(0, 1), sequences)
    assertEquals(sequences.sorted(), sequences)
    assertEquals(listOf("start", "complete"), ledger.map { it["action"] })
  }

  @Test
  fun `task runtime recorder advances shared steps in lockstep with per-phase records`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = false))
    assertEquals("running", stepStatusFor(workflowRepository, workflowId, "preplan"))
    assertRuntimeWorkflowRow(workflowRepository, workflowId, currentStepId = "preplan", workflowStatus = "running")

    assertTrue(recorder.recordRuntimePhase(workflowId, "preplan", status = "completed", finished = true))
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))

    assertTrue(recorder.recordRuntimePhase(workflowId, "plan", status = "running", finished = false))
    assertEquals("running", stepStatusFor(workflowRepository, workflowId, "plan"))
    // The prior completed phase stays completed in the mid-run snapshot.
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))
    assertRuntimeWorkflowRow(workflowRepository, workflowId, currentStepId = "plan", workflowStatus = "running")

    assertTrue(recorder.recordRuntimePhase(workflowId, "plan", status = "completed", finished = true))
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "plan"))

    assertTrue(
      recorder.recordRuntimePhase(
        workflowId,
        "implement",
        status = "blocked",
        finished = false,
        blockedReason = "needs human",
      ),
    )
    assertEquals("blocked", stepStatusFor(workflowRepository, workflowId, "implement"))
    assertRuntimeWorkflowRow(workflowRepository, workflowId, currentStepId = "implement", workflowStatus = "blocked")
    // Untouched downstream phases stay pending.
    assertEquals("pending", stepStatusFor(workflowRepository, workflowId, "review"))
  }

  @Test
  fun `task runtime shared steps agree with the runner record-derived status map across mixed statuses`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = false)
    recorder.recordRuntimePhase(workflowId, "preplan", status = "completed", finished = true)
    recorder.recordRuntimePhase(workflowId, "plan", status = "running", finished = false)
    recorder.recordRuntimePhase(workflowId, "plan", status = "completed", finished = true)
    recorder.recordRuntimePhase(workflowId, "implement", status = "running", finished = false)
    recorder.recordRuntimePhase(
      workflowId,
      "review",
      status = "blocked",
      finished = false,
      blockedReason = "needs human",
    )

    val records = requireNotNull(recorder.loadPhaseRecords(workflowId))
    val recordDerivedStatuses = records.mapValues { (_, record) -> expectedStepStatusForRecord(record) }
    val stepStatusByPhaseId = decodeStepsForTest(workflowRepository, workflowId)
      .filter { (phaseId, _) -> phaseId in records.keys }
      .toMap()
    // AC7: the full per-phase status map shared steps[] carries cannot diverge from what the records
    // imply for ANY status, including the non-completed running/blocked phases.
    assertEquals(recordDerivedStatuses, stepStatusByPhaseId)
    assertEquals(
      mapOf(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "running",
        "review" to "blocked",
      ),
      stepStatusByPhaseId,
    )
  }

  @Test
  fun `task runtime shared step keeps blocked status even when the blocked record carries a finished timestamp`() {
    // F-003: blocked-wins precedence. A blocked record that also carries a non-null finishedAt must
    // map to a blocked step, never collapse to completed via the finishedAt branch.
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(
      workflowId,
      "implement",
      status = "blocked",
      finished = true,
      blockedReason = "needs human",
    )

    val record = requireNotNull(recorder.loadPhaseRecords(workflowId))["implement"]
    assertNotNull(requireNotNull(record).finishedAt)
    assertEquals("blocked", stepStatusFor(workflowRepository, workflowId, "implement"))
  }

  @Test
  fun `task runtime shared step maps a running record with a finished timestamp to completed`() {
    // F-003: finishedAt-wins precedence. A record whose status is still running but which carries a
    // non-null finishedAt must map to completed.
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = true)

    val record = requireNotNull(recorder.loadPhaseRecords(workflowId))["preplan"]
    assertEquals("running", requireNotNull(record).status)
    assertNotNull(record.finishedAt)
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))
  }

  @Test
  fun `task runtime read loud-fails on malformed persisted phase record`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    // Per-phase record missing the required `resolved_agent_id`.
    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_records": {
          "plan": {
            "phase_id": "plan",
            "status": "running",
            "attempt_count": 1,
            "started_at": "2026-06-02T10:00:00Z"
          }
        }
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId))
    workflowRepository.saveFeatureTaskRuntimeWorkflow(record.copy(artifactsJson = malformedArtifactsJson))

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = "implement",
          status = "running",
          attemptCount = 1,
          resolvedAgentId = "agent-implement-1",
          finished = false,
        ),
      )
    }
  }

  @Test
  fun `task runtime ledger append loud-fails on malformed persisted ledger entry`() {
    // Persisted ledger entry missing the required `action`.
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_ledger": [
          {
            "sequence_number": 0,
            "timestamp": "2026-06-02T10:00:00Z",
            "phase_id": "plan",
            "attempt_count": 1
          }
        ]
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId))
    workflowRepository.saveFeatureTaskRuntimeWorkflow(record.copy(artifactsJson = malformedArtifactsJson))

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME)
    }
  }

  @Test
  fun `task runtime ledger append loud-fails when persisted ledger is not a list`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_ledger": {"not": "a list"}
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId))
    workflowRepository.saveFeatureTaskRuntimeWorkflow(record.copy(artifactsJson = malformedArtifactsJson))

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME)
    }
  }

  @Test
  fun `task runtime ledger seeds next sequence from persisted max across a re-read`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, WorkflowSnapshotValidatorInfraAdapter())

    val opened = service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.START))
    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.COMPLETE))
    // A separate append must continue from the persisted max rather than rewinding to 0.
    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME))

    val artifacts = decodeArtifactsForTest(
      requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(listOf(0, 1, 2), sequences)
    assertEquals(listOf("start", "complete", "resume"), ledger.map { it["action"] })
  }

  @Test
  fun `workflow service hydrates implement session summary for continuation payloads`() {
    val workflowRepository =
      InMemoryWorkflowStateRepository(
        implementSessionSummary =
        FeatureImplementSessionSummary(
          sessionId = "fis-001",
          issueKeyProvided = true,
          issueKeyType = "other",
          specInputTypes = listOf("markdown_file"),
          specWordCount = 42,
          featureSize = "MEDIUM",
          featureName = "workflow-runtime",
          rolloutNeeded = false,
          acceptanceCriteriaCount = 6,
          openQuestionsCount = 0,
          specSummary = "Port workflow runtime",
        ),
      )
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard
    val sessionSummary = continued.view.sessionSummary

    assertEquals("fis-001", sessionSummary["session_id"])
    assertEquals(listOf("markdown_file"), sessionSummary["spec_input_types"])
    assertEquals("workflow-runtime", sessionSummary["feature_name"])
    assertEquals("Port workflow runtime", sessionSummary["spec_summary"])
  }

  @Test
  fun `workflow service writes decomposition manifest when implement plan decomposes`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "running",
        currentStepId = "plan",
        stepUpdates = listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch =
        mapOf(
          "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
          "plan" to
            mapOf(
              "mode" to "decompose",
              "parent_spec_path" to parentSpec.toString(),
              "recommended_first_subtask_id" to 1,
              "subtasks" to
                listOf(
                  mapOf(
                    "id" to 1,
                    "name" to "foundation",
                    "spec_path" to parentSpec.parent.resolve("spec_subtask_1_foundation.md").toString(),
                    "depends_on" to emptyList<Int>(),
                  ),
                ),
            ),
        ),
      ),
      dbOverride = null,
    )

    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertTrue(Files.isRegularFile(manifest), "Decomposition manifest should be written beside parent spec.")
    assertTrue(Files.readString(manifest).contains("same_branch_commit_per_subtask"))
  }

  @Test
  fun `workflow service does not add decomposition runtime for single spec implement plan`() {
    val tempDir = Files.createTempDirectory("skillbill-app-single-spec")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-single/spec.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val updated = service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
        artifactsPatch =
        mapOf(
          "plan" to
            mapOf(
              "mode" to "implement",
              "task_count" to 1,
              "parent_spec_path" to parentSpec.toString(),
            ),
        ),
      ),
      dbOverride = null,
    ) as WorkflowUpdateResult.Ok

    val persisted = service.get(WorkflowFamilyKind.TASK_PROSE, workflowId, dbOverride = null) as WorkflowGetResult.Ok
    val artifacts = persisted.snapshot.artifacts
    assertEquals("implement", (artifacts["plan"] as Map<*, *>)["mode"])
    assertFalse(artifacts.containsKey("decomposition_runtime"))
    assertFalse(Files.exists(parentSpec.parent.resolve("decomposition-manifest.yaml")))
  }

  @Test
  fun `workflow service does not write decomposition projection when durable save fails`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-save-fails")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    workflowRepository.failNextImplementSave = true
    assertFailsWith<IllegalStateException> {
      service.update(
        WorkflowFamilyKind.TASK_PROSE,
        WorkflowUpdateRequest(
          workflowId = workflowId,
          workflowStatus = "running",
          currentStepId = "plan",
          stepUpdates = listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
          artifactsPatch = decompositionPlanPatch(parentSpec, subtaskSpec),
        ),
        dbOverride = null,
      )
    }

    assertFalse(Files.exists(parentSpec.parent.resolve("decomposition-manifest.yaml")))
  }

  @Test
  fun `workflow service updates decomposition subtask runtime status for blocked and skipped outcomes`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-state")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    Files.writeString(subtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val workflowId = createDecompositionWorkflow(service, parentSpec, subtaskSpec)

    markDecompositionSubtaskBlocked(service, workflowId, subtaskSpec)

    val blockedManifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val blockedSubtask = blockedManifest.subtasks.single()
    assertEquals("blocked", blockedSubtask.status)
    assertEquals("runtime: Validation failed.", blockedSubtask.blockedReason)
    assertEquals("validate", blockedSubtask.lastResumableStep)
    assertEquals("Blocked", statusLine(subtaskSpec))

    markDecompositionSubtaskSkipped(service, workflowId, subtaskSpec)

    val skippedManifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals("skipped", skippedManifest.subtasks.single().status)
    assertEquals("complete", skippedManifest.currentSubtaskIntent.action)
    assertEquals("Skipped", statusLine(subtaskSpec))
  }

  @Test
  fun `workflow service reopens blocked decomposition subtask runtime state on continuation`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-reopen")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    Files.writeString(subtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val workflowId = createDecompositionWorkflow(service, parentSpec, subtaskSpec)

    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "blocked",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "blocked", "attempt_count" to 1)),
        artifactsPatch =
        mapOf(
          "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
          "audit_report" to mapOf("gap_count" to 0),
          "blocked_reason" to "Validation paused.",
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val subtask = manifest.subtasks.single()
    assertEquals("reopened", continued.view.continueStatus)
    assertEquals("in_progress", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals("validate", subtask.lastResumableStep)
    assertEquals("In Progress", statusLine(subtaskSpec))
  }

  @Test
  fun `workflow service continues decomposed parent issue key by starting first dependency-complete subtask`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-start")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val git = FakeWorkflowGitOperations()
    val service = testWorkflowService(FakeDatabaseSessionFactory(workflows = workflowRepository), git)
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals(1, continued.decompositionSubtaskId)
    assertEquals("SKILL-51", continued.issueKey)
    assertEquals("SKILL-51", continued.outcome?.issueKey)
    assertEquals(1, continued.outcome?.subtaskId)
    assertEquals("in_progress", continued.outcome?.status)
    assertEquals("preplan", continued.outcome?.lastResumableStep)
    assertEquals("preplan", continued.view.continueStepId)
    assertEquals("in_progress", manifest.subtasks.first { it.id == 1 }.status)
    assertEquals("preplan", manifest.subtasks.first { it.id == 1 }.lastResumableStep)
    assertEquals(listOf("feat/SKILL-51-demo@main"), git.checkouts)
  }

  @Test
  fun `workflow service constrains decomposed issue key continuation to requested subtask`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-subtask-constraint")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository()),
      FakeWorkflowGitOperations(),
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)

    val blocked = service.continueWorkflow(
      WorkflowFamilyKind.TASK_PROSE,
      "SKILL-51",
      subtaskId = 2,
      dbOverride = null,
    ) as WorkflowContinueResult.DecompositionBlockedSubtask

    assertEquals(2, blocked.subtaskId)
    assertEquals("Requested subtask 2 is not the next runnable subtask for SKILL-51.", blocked.blockedReason)
  }

  @Test
  fun `workflow service records same branch subtask commit before starting next subtask`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-commit")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val git = FakeWorkflowGitOperations(commitSha = "abc123")
    val service = testWorkflowService(FakeDatabaseSessionFactory(workflows = workflowRepository), git)
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals(2, continued.decompositionSubtaskId)
    assertEquals(null, manifest.subtasks.first { it.id == 1 }.commitSha)
    assertEquals(listOf("SKILL-51 subtask 1: foundation"), git.commits)
  }

  @Test
  fun `workflow service does not auto commit earlier completed subtasks when explicit subtask requested`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-explicit-no-advance")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val git = FakeWorkflowGitOperations(commitSha = "abc123")
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository()),
      git,
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(
      WorkflowFamilyKind.TASK_PROSE,
      "SKILL-51",
      subtaskId = 2,
      dbOverride = null,
    ) as WorkflowContinueResult.DecompositionStandard

    assertEquals(2, continued.decompositionSubtaskId)
    assertEquals(emptyList(), git.commits)
  }

  @Test
  fun `workflow service records pr suppressed commit completion as durable subtask outcome`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-headless-complete")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = workflowRepository),
      FakeWorkflowGitOperations(),
    )
    val parentWorkflowId = createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = first.view.resume.snapshot.workflowId,
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "assessment" to mapOf("spec_path" to subtaskOne.toString()),
          "goal_continuation" to mapOf("enabled" to true, "suppress_pr" to true),
          "commit_push_result" to mapOf("commit_sha" to "abc123"),
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val parent = service.get(WorkflowFamilyKind.TASK_PROSE, parentWorkflowId, dbOverride = null) as WorkflowGetResult.Ok
    val runtime = parent.snapshot.artifacts["decomposition_runtime"] as Map<*, *>
    val firstRuntimeSubtask = (runtime["subtasks"] as List<*>)
      .filterIsInstance<Map<*, *>>()
      .single { it["id"] == 1 }

    assertEquals(2, continued.decompositionSubtaskId)
    assertEquals(null, manifest.subtasks.first { it.id == 1 }.commitSha)
    assertEquals("complete", firstRuntimeSubtask["status"])
    assertEquals("abc123", firstRuntimeSubtask["commit_sha"])
    assertEquals(first.view.resume.snapshot.workflowId, firstRuntimeSubtask["workflow_id"])
    assertEquals(null, firstRuntimeSubtask["blocked_reason"])
    assertEquals("commit_push", firstRuntimeSubtask["last_resumable_step"])
    assertEquals("SKILL-51", continued.outcome?.issueKey)
    assertEquals(2, continued.outcome?.subtaskId)
    assertEquals("in_progress", continued.outcome?.status)
    assertEquals(null, continued.outcome?.blockedReason)
    assertEquals("preplan", continued.outcome?.lastResumableStep)
  }

  @Test
  fun `workflow service blocks pr suppressed commit completion without durable commit sha`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-headless-missing-sha")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = workflowRepository),
      FakeWorkflowGitOperations(),
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = first.view.resume.snapshot.workflowId,
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "assessment" to mapOf("spec_path" to subtaskOne.toString()),
          "goal_continuation" to mapOf("enabled" to true, "suppress_pr" to true),
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionBlockedSubtask
    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val blockedSubtask = manifest.subtasks.first { it.id == 1 }

    assertEquals(1, continued.subtaskId)
    assertEquals("blocked", blockedSubtask.status)
    assertEquals(null, blockedSubtask.commitSha)
    assertEquals(
      "git: Goal-continuation commit_push completed without commit_push_result.commit_sha.",
      blockedSubtask.blockedReason,
    )
  }

  @Test
  fun `workflow service returns requested terminal subtask outcome without advancing later subtasks`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-terminal-subtask")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository()),
      FakeWorkflowGitOperations(),
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(
      WorkflowFamilyKind.TASK_PROSE,
      "SKILL-51",
      subtaskId = 1,
      dbOverride = null,
    ) as WorkflowContinueResult.DecompositionSubtaskOutcome
    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))

    assertEquals(1, continued.subtaskId)
    assertEquals("complete", continued.outcome.status)
    assertEquals("pr_description", continued.outcome.lastResumableStep)
    assertEquals("pending", manifest.subtasks.first { it.id == 2 }.status)
  }

  @Test
  fun `workflow service completes all subtasks and projects parent spec status`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-complete")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val git = FakeWorkflowGitOperations(commitSha = "abc123")
    val service = testWorkflowService(FakeDatabaseSessionFactory(workflows = workflowRepository), git)
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)
    val second = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, second.view.resume.snapshot.workflowId, subtaskTwo)

    val done = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionDone

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals("complete", done.decompositionStatus)
    assertEquals("complete", manifest.status)
    assertTrue(manifest.subtasks.all { it.status == "complete" })
    assertTrue(manifest.subtasks.all { it.commitSha == null })
    assertEquals(listOf("SKILL-51 subtask 1: foundation", "SKILL-51 subtask 2: runtime"), git.commits)
    assertEquals("Complete", statusLine(parentSpec))
    assertEquals("Complete", statusSection(parentSpec))
    assertEquals("Complete", statusLine(subtaskOne))
    assertEquals("Complete", statusLine(subtaskTwo))
  }

  @Test
  fun `workflow service records blocked status when same branch subtask commit fails`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-commit-fails")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = workflowRepository),
      FakeWorkflowGitOperations(commitError = "missing git identity"),
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionBlockedGit

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val blocked = manifest.subtasks.first { it.id == 1 }
    assertEquals("missing git identity", continued.blockedReason)
    assertEquals("blocked", manifest.status)
    assertEquals("blocked", blocked.status)
    assertEquals("missing git identity", blocked.blockedReason)
    assertEquals("commit_push", blocked.lastResumableStep)
    assertEquals(null, blocked.commitSha)
  }

  @Test
  fun `workflow service checks stacked subtask branch base before starting subtask`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-stacked")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val git = FakeWorkflowGitOperations()
    val service = testWorkflowService(FakeDatabaseSessionFactory(workflows = workflowRepository), git)
    createDecompositionWorkflow(
      service = service,
      parentSpec = parentSpec,
      subtaskOne = subtaskOne,
      subtaskTwo = subtaskTwo,
      executionModel = "stacked_branches",
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)

    assertTrue(
      continued is WorkflowContinueResult.DecompositionStandard ||
        continued is WorkflowContinueResult.Standard,
      "Expected ok continuation, got $continued",
    )
    assertEquals(listOf("feat/SKILL-51-demo-1@main"), git.checkouts)
    assertEquals(listOf("feat/SKILL-51-demo-1@main"), git.baseValidations)
  }

  @Test
  fun `workflow service stops issue key continuation on blocked subtask reason`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-blocked")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = workflowRepository),
      FakeWorkflowGitOperations(),
    )
    val workflowId = createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    markDecompositionSubtaskBlocked(service, workflowId, subtaskOne)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionBlockedSubtask

    assertEquals("runtime: Validation failed.", continued.blockedReason)
    assertEquals(1, continued.subtaskId)
  }

  @Test
  fun `workflow service resumes in-progress decomposed subtask by issue key`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-resume")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val service = testWorkflowService(
      FakeDatabaseSessionFactory(workflows = workflowRepository),
      FakeWorkflowGitOperations(),
    )
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    val subtaskWorkflowId = first.view.resume.snapshot.workflowId
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = subtaskWorkflowId,
        workflowStatus = "running",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "audit_report" to mapOf("gap_count" to 0),
          "validation_result" to mapOf("passed" to false),
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    assertEquals("already_running", continued.view.continueStatus)
    assertEquals(subtaskWorkflowId, continued.view.resume.snapshot.workflowId)
    assertEquals("validate", continued.view.continueStepId)
    assertEquals(1, continued.decompositionSubtaskId)
  }

  @Test
  fun `workflow service owns verify prior steps done resume and reopened continuation`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened = service.open(WorkflowFamilyKind.VERIFY, currentStepId = "code_review", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val steps = opened.snapshot.steps
    assertEquals("completed", steps.single { it.stepId == "gather_diff" }.status)

    service.update(
      WorkflowFamilyKind.VERIFY,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "running",
        currentStepId = "verdict",
        stepUpdates = listOf(mapOf("step_id" to "verdict", "status" to "blocked", "attempt_count" to 1)),
        artifactsPatch =
        mapOf(
          "criteria_summary" to emptyMap<String, Any?>(),
          "diff_summary" to emptyMap(),
          "review_result" to emptyMap(),
          "unit_test_value_result" to emptyMap(),
          "completeness_audit_result" to emptyMap(),
        ),
      ),
      dbOverride = null,
    )

    val resumed = service.resume(WorkflowFamilyKind.VERIFY, workflowId, dbOverride = null) as WorkflowResumeResult.Ok
    val continued = service.continueWorkflow(WorkflowFamilyKind.VERIFY, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard
    val afterContinue = service.get(WorkflowFamilyKind.VERIFY, workflowId, dbOverride = null) as WorkflowGetResult.Ok
    val continuedSteps = afterContinue.snapshot.steps

    assertEquals("resume", resumed.resume.resumeMode)
    assertEquals("reopened", continued.view.continueStatus)
    assertEquals("running", continuedSteps.single { it.stepId == "verdict" }.status)
    assertEquals(2, continuedSteps.single { it.stepId == "verdict" }.attemptCount)
  }

  @Test
  fun `workflow service hydrates verify session summary for continuation payloads`() {
    val workflowRepository =
      InMemoryWorkflowStateRepository(
        verifySessionSummary =
        FeatureVerifySessionSummary(
          sessionId = "fvr-001",
          acceptanceCriteriaCount = 3,
          rolloutRelevant = true,
          specSummary = "Verify workflow runtime",
        ),
      )
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened = service.open(WorkflowFamilyKind.VERIFY, sessionId = "fvr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val continued = service.continueWorkflow(WorkflowFamilyKind.VERIFY, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard
    val sessionSummary = continued.view.sessionSummary

    assertEquals("fvr-001", sessionSummary["session_id"])
    assertEquals(3, sessionSummary["acceptance_criteria_count"])
    assertEquals(true, sessionSummary["rollout_relevant"])
    assertEquals("Verify workflow runtime", sessionSummary["spec_summary"])
  }

  @Test
  fun `lifecycle telemetry port records goal events mapped from requests`() {
    val repository = RecordingGoalLifecycleTelemetryRepository()

    repository.goalStarted(goalStartedRequest().toRecord(), level = "full")
    repository.goalSubtaskFinished(goalSubtaskFinishedRequest().toRecord(), level = "full")
    repository.goalFinished(goalFinishedRequest().toRecord(), level = "full")

    val started = repository.startedRecords.single()
    assertEquals("SKILL-66", started.issueKey)
    assertEquals("goal telemetry", started.featureName)
    assertEquals("wf-goal-1", started.workflowId)
    assertEquals(4, started.subtaskTotal)
    assertTrue(started.resumed)
    assertEquals("2026-06-04T10:00:00Z", started.startedAt)

    val subtask = repository.subtaskRecords.single()
    assertEquals(2, subtask.subtaskId)
    assertEquals("persistence", subtask.subtaskName)
    assertEquals("blocked", subtask.status)
    assertEquals(240_000L, subtask.durationMs)
    assertEquals(3, subtask.attemptCount)
    assertEquals("validation failed", subtask.blockedReason)

    val finished = repository.finishedRecords.single()
    assertEquals("blocked", finished.status)
    assertEquals(1_200_000L, finished.durationMs)
    assertEquals(1, finished.subtasksComplete)
    assertEquals(1, finished.subtasksBlocked)
    assertEquals(0, finished.subtasksSkipped)
  }

  @Test
  fun `workflow stats port exposes goal aggregate through its surface`() {
    val expected =
      GoalWorkflowStats(
        totalRuns = 2,
        finishedRuns = 1,
        inProgressRuns = 1,
        completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
        completedRuns = 1,
        completedRate = 1.0,
        blockedRuns = 0,
        blockedRate = 0.0,
        subtaskOutcomeCounts = mapOf("complete" to 3, "blocked" to 0, "skipped" to 1),
        totalSubtaskEvents = 4,
        averageRunDurationMs = 5_460_000.0,
        averageSubtaskDurationMs = 120_000.0,
        averageAttemptCount = 1.25,
        mostRecentRun =
        GoalRunSummary(
          workflowId = "wf-goal-9",
          issueKey = "SKILL-66",
          featureName = "goal telemetry",
          status = "completed",
          startedAt = "2026-06-04T10:00:00Z",
          finishedAt = "2026-06-04T11:31:00Z",
          durationMs = 5_460_000L,
          resumed = false,
          subtaskTotal = 4,
        ),
        topBlockedSubtasks = emptyList(),
      )
    val repository: WorkflowStatsRepository = FakeGoalStatsRepository(expected)

    assertEquals(expected, repository.goalStats())
    assertEquals("wf-goal-9", repository.goalStats().mostRecentRun?.workflowId)
  }

  @Test
  fun `review service goalStats returns GoalStatsResult from seeded repository`() {
    val blockedSummary = GoalBlockedSubtaskSummary(
      subtaskId = 2,
      subtaskName = "persistence",
      issueKey = "SKILL-66",
      blockedReason = "validation failed",
      attemptCount = 3,
    )
    val seededStats = GoalWorkflowStats(
      totalRuns = 1,
      finishedRuns = 1,
      inProgressRuns = 0,
      completionStatusCounts = mapOf("completed" to 0, "blocked" to 1),
      completedRuns = 0,
      completedRate = 0.0,
      blockedRuns = 1,
      blockedRate = 1.0,
      subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 1, "skipped" to 0),
      totalSubtaskEvents = 1,
      averageRunDurationMs = 1_200_000.0,
      averageSubtaskDurationMs = 240_000.0,
      averageAttemptCount = 3.0,
      mostRecentRun = null,
      topBlockedSubtasks = listOf(blockedSummary),
    )
    val database = FakeDatabaseSessionFactory(reviews = FakeGoalStatsReviewRepository(seededStats))
    val service = ReviewService(
      EnvironmentContext(environment = emptyMap(), userHome = Files.createTempDirectory("skillbill-app-goal")),
      database,
      FakeTelemetrySettingsProvider(enabled = false),
      FakeReviewInputSource,
      EmptyReviewAttributionPort,
    )

    val result: GoalStatsResult = service.goalStats(dbOverride = null)

    assertEquals(listOf("read"), database.calls)
    assertEquals("/fake/metrics.db", result.dbPath)
    assertEquals(seededStats, result.stats)
    assertEquals(1, result.stats.topBlockedSubtasks.size)
    assertEquals("validation failed", result.stats.topBlockedSubtasks.single().blockedReason)
  }

  @Test
  fun `goal stats all-blocked store has blocked rate 1 and non-empty topBlockedSubtasks`() {
    val blockedEntry = GoalBlockedSubtaskSummary(
      subtaskId = 1,
      subtaskName = "implement",
      issueKey = "SKILL-99",
      blockedReason = "compile error",
      attemptCount = 2,
    )
    val allBlockedStats = GoalWorkflowStats(
      totalRuns = 1,
      finishedRuns = 1,
      inProgressRuns = 0,
      completionStatusCounts = mapOf("completed" to 0, "blocked" to 1),
      completedRuns = 0,
      completedRate = 0.0,
      blockedRuns = 1,
      blockedRate = 1.0,
      subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 1, "skipped" to 0),
      totalSubtaskEvents = 1,
      averageRunDurationMs = 60_000.0,
      averageSubtaskDurationMs = 60_000.0,
      averageAttemptCount = 2.0,
      mostRecentRun = null,
      topBlockedSubtasks = listOf(blockedEntry),
    )

    assertEquals(1.0, allBlockedStats.blockedRate)
    assertEquals(0.0, allBlockedStats.completedRate)
    assertEquals(1, allBlockedStats.topBlockedSubtasks.size)
    assertEquals("compile error", allBlockedStats.topBlockedSubtasks.single().blockedReason)
  }

  @Test
  fun `goal stats all-skipped subtasks has empty topBlockedSubtasks`() {
    val allSkippedStats = GoalWorkflowStats(
      totalRuns = 1,
      finishedRuns = 1,
      inProgressRuns = 0,
      completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
      completedRuns = 1,
      completedRate = 1.0,
      blockedRuns = 0,
      blockedRate = 0.0,
      subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 0, "skipped" to 3),
      totalSubtaskEvents = 3,
      averageRunDurationMs = 100_000.0,
      averageSubtaskDurationMs = 0.0,
      averageAttemptCount = 0.0,
      mostRecentRun = null,
      topBlockedSubtasks = emptyList(),
    )

    assertEquals(3, allSkippedStats.subtaskOutcomeCounts["skipped"])
    assertTrue(allSkippedStats.topBlockedSubtasks.isEmpty())
  }

  @Test
  fun `goal stats single-run store has non-null mostRecentRun and totalRuns equals 1`() {
    val singleRunSummary = GoalRunSummary(
      workflowId = "wf-single",
      issueKey = "SKILL-1",
      featureName = "single run feature",
      status = "completed",
      startedAt = "2026-06-05T10:00:00Z",
      finishedAt = "2026-06-05T10:30:00Z",
      durationMs = 1_800_000L,
      resumed = false,
      subtaskTotal = 2,
    )
    val singleRunStats = GoalWorkflowStats(
      totalRuns = 1,
      finishedRuns = 1,
      inProgressRuns = 0,
      completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
      completedRuns = 1,
      completedRate = 1.0,
      blockedRuns = 0,
      blockedRate = 0.0,
      subtaskOutcomeCounts = mapOf("complete" to 2, "blocked" to 0, "skipped" to 0),
      totalSubtaskEvents = 2,
      averageRunDurationMs = 1_800_000.0,
      averageSubtaskDurationMs = 900_000.0,
      averageAttemptCount = 1.0,
      mostRecentRun = singleRunSummary,
      topBlockedSubtasks = emptyList(),
    )

    assertEquals(1, singleRunStats.totalRuns)
    assertEquals("wf-single", requireNotNull(singleRunStats.mostRecentRun).workflowId)
  }
}

private class FakeDatabaseSessionFactory(
  private val reviews: ReviewRepository = FakeReviewRepository(),
  private val learnings: LearningRepository = FakeLearningRepository(),
  private val telemetryOutbox: TelemetryOutboxRepository = NoopTelemetryOutboxRepository,
  private val telemetryReconciliation: TelemetryReconciliationRepository = NoopTelemetryReconciliationRepository,
  private val workflows: WorkflowStateRepository = NoopWorkflowStateRepository,
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
    override val lifecycleTelemetry: LifecycleTelemetryRepository = NoopLifecycleTelemetryRepository
    override val telemetryReconciliation: TelemetryReconciliationRepository =
      this@FakeDatabaseSessionFactory.telemetryReconciliation
    override val telemetryOutbox: TelemetryOutboxRepository = this@FakeDatabaseSessionFactory.telemetryOutbox
    override val workflowStates: WorkflowStateRepository = this@FakeDatabaseSessionFactory.workflows
  }
}

private object NoopTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(level: String): TelemetryReconciliationResult =
    TelemetryReconciliationResult.Empty
}

private class RecordingTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  val levels = mutableListOf<String>()

  override fun reconcileStaleSessions(level: String): TelemetryReconciliationResult {
    levels += level
    return TelemetryReconciliationResult.Empty
  }
}

private object ThrowingTelemetryReconciliationRepository : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(level: String): TelemetryReconciliationResult =
    error("SQLITE_BUSY: database is locked")
}

@Suppress("TooManyFunctions") // mirrors the full LifecycleTelemetryRepository contract
private object NoopLifecycleTelemetryRepository : LifecycleTelemetryRepository {
  override fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String) = Unit

  override fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String) = Unit

  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) = Unit

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) = Unit

  override fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String) = Unit

  override fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String) = Unit

  override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) = Unit

  override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) = Unit

  override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) = Unit

  override fun goalStarted(record: GoalStartedRecord, level: String) = Unit

  override fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String) = Unit

  override fun goalFinished(record: GoalFinishedRecord, level: String) = Unit

  override fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String) = Unit
}

private fun goalStartedRequest(): GoalStartedRequest = GoalStartedRequest(
  issueKey = "SKILL-66",
  featureName = "goal telemetry",
  workflowId = "wf-goal-1",
  subtaskTotal = 4,
  resumed = true,
  startedAt = "2026-06-04T10:00:00Z",
  mode = "runtime",
)

private fun goalSubtaskFinishedRequest(): GoalSubtaskFinishedRequest = GoalSubtaskFinishedRequest(
  issueKey = "SKILL-66",
  workflowId = "wf-goal-1",
  subtaskId = 2,
  subtaskName = "persistence",
  status = "blocked",
  startedAt = "2026-06-04T10:05:00Z",
  finishedAt = "2026-06-04T10:09:00Z",
  durationMs = 240_000L,
  attemptCount = 3,
  blockedReason = "validation failed",
)

private fun goalFinishedRequest(): GoalFinishedRequest = GoalFinishedRequest(
  issueKey = "SKILL-66",
  workflowId = "wf-goal-1",
  status = "blocked",
  startedAt = "2026-06-04T10:00:00Z",
  finishedAt = "2026-06-04T10:20:00Z",
  durationMs = 1_200_000L,
  subtasksComplete = 1,
  subtasksBlocked = 1,
  subtasksSkipped = 0,
  mode = "runtime",
)

@Suppress("TooManyFunctions") // mirrors the full LifecycleTelemetryRepository contract
private class RecordingGoalLifecycleTelemetryRepository : LifecycleTelemetryRepository {
  val startedRecords = mutableListOf<GoalStartedRecord>()
  val subtaskRecords = mutableListOf<GoalSubtaskFinishedRecord>()
  val finishedRecords = mutableListOf<GoalFinishedRecord>()
  val issueFinishedRecords = mutableListOf<GoalIssueFinishedRecord>()

  override fun goalStarted(record: GoalStartedRecord, level: String) {
    startedRecords += record
  }

  override fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String) {
    subtaskRecords += record
  }

  override fun goalFinished(record: GoalFinishedRecord, level: String) {
    finishedRecords += record
  }

  override fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String) {
    issueFinishedRecords += record
  }

  override fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String) = error("unused")

  override fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String) = error("unused")

  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) = error("unused")

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) = error("unused")

  override fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String) = error("unused")

  override fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String) = error("unused")

  override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) = error("unused")

  override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) = error("unused")

  override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) = error("unused")
}

private class FakeGoalStatsRepository(
  private val stats: GoalWorkflowStats,
) : WorkflowStatsRepository {
  override fun featureImplementStats(): FeatureImplementWorkflowStats = error("Unexpected featureImplementStats")

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = stats
}

private class FakeGoalStatsReviewRepository(
  private val stats: GoalWorkflowStats,
) : ReviewRepository {
  override fun saveImportedReview(review: ImportedReview, sourcePath: String?) = error("Unexpected saveImportedReview")

  override fun markOrchestrated(runId: String) = error("Unexpected markOrchestrated")

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = error("Unexpected updateReviewFinishedTelemetryState")

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = error("Unexpected recordFeedback")

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> = error("Unexpected fetchNumberedFindings")

  override fun findingExists(runId: String, findingId: String): Boolean = error("Unexpected findingExists")

  override fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome? =
    error("Unexpected latestRejectedLearningSourceOutcome")

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot = error("Unexpected reviewStats")

  override fun featureImplementStats(): FeatureImplementWorkflowStats = error("Unexpected featureImplementStats")

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = stats
}

private object FakeReviewInputSource : ReviewInputSource {
  override fun readInput(inputPath: String, stdinText: String?): Pair<String, String?> = (stdinText ?: "") to null
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

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = null

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? {
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

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot = error("Unexpected reviewStats")

  override fun featureImplementStats(): FeatureImplementWorkflowStats = error("Unexpected featureImplementStats")

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = error("Unexpected featureVerifyStats")

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats = error("Unexpected featureTaskRuntimeStats")

  override fun goalStats(): GoalWorkflowStats = error("Unexpected goalStats")
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
  val enqueuedEventNames = mutableListOf<String>()

  override fun enqueue(eventName: String, payloadJson: String): Long {
    val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
    enqueuedEventNames += eventName
    rows += TelemetryOutboxRecord(
      id = id,
      eventName = eventName,
      payloadJson = payloadJson,
      createdAt = "2026-04-24 00:00:00",
      syncedAt = null,
      lastError = "",
    )
    return id
  }

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

  override fun read(): TelemetryConfigDocument? = null

  override fun ensure(): TelemetryConfigDocument = TelemetryConfigDocument(emptyMap())

  override fun write(document: TelemetryConfigDocument) = Unit

  override fun delete(): Boolean = true
}

private class FakeTelemetryClient : TelemetryClient {
  val sentBatchIds = mutableListOf<List<Long>>()

  override fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>) {
    sentBatchIds += rows.map { it.id }
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities =
    error("Unexpected fetchProxyCapabilities")

  override fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): TelemetryRemoteStatsResult =
    error("Unexpected fetchRemoteStats")
}

private object NoopWorkflowStateRepository : WorkflowStateRepository {
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? = null
}

private fun createDecompositionWorkflow(service: WorkflowService, parentSpec: Path, subtaskSpec: Path): String =
  createDecompositionWorkflow(service, parentSpec, subtaskSpec, null)

private fun createDecompositionWorkflow(
  service: WorkflowService,
  parentSpec: Path,
  subtaskOne: Path,
  subtaskTwo: Path?,
  executionModel: String = "same_branch_commit_per_subtask",
): String {
  val opened = service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001", dbOverride = null)
    as WorkflowOpenResult.Ok
  val workflowId = opened.workflowId
  service.update(
    WorkflowFamilyKind.TASK_PROSE,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = "running",
      currentStepId = "plan",
      stepUpdates = listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
      artifactsPatch = decompositionPlanPatch(parentSpec, subtaskOne, subtaskTwo, executionModel),
    ),
    dbOverride = null,
  )
  return workflowId
}

private fun markDecompositionSubtaskBlocked(service: WorkflowService, workflowId: String, subtaskSpec: Path) {
  service.update(
    WorkflowFamilyKind.TASK_PROSE,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = "blocked",
      currentStepId = "validate",
      stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "blocked", "attempt_count" to 1)),
      artifactsPatch =
      mapOf(
        "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
        "validation_result" to mapOf("passed" to false),
        "blocked_reason" to "Validation failed.",
      ),
    ),
    dbOverride = null,
  )
}

private fun markDecompositionSubtaskSkipped(service: WorkflowService, workflowId: String, subtaskSpec: Path) {
  service.update(
    WorkflowFamilyKind.TASK_PROSE,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = "running",
      currentStepId = "pr_description",
      stepUpdates = listOf(mapOf("step_id" to "pr_description", "status" to "skipped", "attempt_count" to 1)),
      artifactsPatch = mapOf("assessment" to mapOf("spec_path" to subtaskSpec.toString())),
    ),
    dbOverride = null,
  )
}

private fun markDecompositionSubtaskComplete(service: WorkflowService, workflowId: String, subtaskSpec: Path) {
  service.update(
    WorkflowFamilyKind.TASK_PROSE,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = "completed",
      currentStepId = "pr_description",
      stepUpdates = listOf(mapOf("step_id" to "pr_description", "status" to "completed", "attempt_count" to 1)),
      artifactsPatch = mapOf("assessment" to mapOf("spec_path" to subtaskSpec.toString())),
    ),
    dbOverride = null,
  )
}

private fun decompositionPlanPatch(
  parentSpec: Path,
  subtaskSpec: Path,
  subtaskTwo: Path? = null,
  executionModel: String = "same_branch_commit_per_subtask",
): Map<String, Any?> {
  val subtasks = mutableListOf(
    mapOf(
      "id" to 1,
      "name" to "foundation",
      "spec_path" to subtaskSpec.toString(),
      "depends_on" to emptyList<Int>(),
    ),
  )
  if (subtaskTwo != null) {
    subtasks += mapOf(
      "id" to 2,
      "name" to "runtime",
      "spec_path" to subtaskTwo.toString(),
      "depends_on" to listOf(1),
    )
  }
  val plan = linkedMapOf<String, Any?>(
    "mode" to "decompose",
    "parent_spec_path" to parentSpec.toString(),
    "recommended_first_subtask_id" to 1,
    "subtasks" to subtasks,
  )
  if (executionModel == "stacked_branches") {
    plan["execution_model"] = "stacked_branches"
    plan["base_branch"] = "main"
    plan["stack_branches"] = listOf(
      mapOf("subtask_id" to 1, "branch" to "feat/SKILL-51-demo-1", "base_branch" to "main"),
      mapOf("subtask_id" to 2, "branch" to "feat/SKILL-51-demo-2", "base_branch" to "feat/SKILL-51-demo-1"),
    ).take(subtasks.size)
  }
  return mapOf(
    "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
    "plan" to plan,
  )
}

private fun writeSpecs(parentSpec: Path, vararg subtasks: Path) {
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "---\nstatus: Pending\n---\n\n# Parent\n\n## Status\n\nPending\n")
  subtasks.forEach { subtask ->
    Files.writeString(subtask, "---\nstatus: Pending\n---\n\n# Subtask")
  }
}

private fun statusLine(path: Path): String =
  Files.readAllLines(path).first { it.startsWith("status: ") }.removePrefix("status: ")

private fun statusSection(path: Path): String {
  val lines = Files.readAllLines(path)
  val statusHeading = lines.indexOf("## Status")
  return lines.drop(statusHeading + 1).first(String::isNotBlank)
}

private fun decodeArtifactsForTest(artifactsJson: String): Map<String, Any?> =
  JsonSupport.parseObjectOrNull(artifactsJson)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    .orEmpty()

private fun FeatureTaskRuntimePhaseRecorder.appendPlanLedger(
  workflowId: String,
  action: FeatureTaskRuntimePhaseLedgerAction,
): Boolean = appendLedgerEntry(
  FeatureTaskRuntimePhaseLedgerRequest(
    workflowId = workflowId,
    action = action,
    phaseId = "plan",
    attemptCount = 1,
    resolvedAgentId = "agent-plan-1",
  ),
)

private fun FeatureTaskRuntimePhaseRecorder.recordPlanPhase(
  workflowId: String,
  status: String,
  finished: Boolean,
  outputArtifact: String? = null,
): Boolean = recordPhaseState(
  FeatureTaskRuntimePhaseStateRequest(
    workflowId = workflowId,
    phaseId = "plan",
    status = status,
    attemptCount = 1,
    resolvedAgentId = "agent-plan-1",
    finished = finished,
    outputArtifact = outputArtifact,
  ),
)

private fun FeatureTaskRuntimePhaseRecorder.recordRuntimePhase(
  workflowId: String,
  phaseId: String,
  status: String,
  finished: Boolean,
  blockedReason: String? = null,
): Boolean = recordPhaseState(
  FeatureTaskRuntimePhaseStateRequest(
    workflowId = workflowId,
    phaseId = phaseId,
    status = status,
    attemptCount = 1,
    resolvedAgentId = "agent-$phaseId-1",
    finished = finished,
    blockedReason = blockedReason,
  ),
)

// Mirrors the production stepStatusForPhaseRecord precedence so the AC7 status-map assertion proves
// agreement against an independent derivation rather than re-reading steps[]: blocked wins, then a
// finished timestamp maps to completed, otherwise the record's own status carries through.
private fun expectedStepStatusForRecord(record: FeatureTaskRuntimePhaseRecord): String = when {
  record.status == "blocked" -> "blocked"
  record.finishedAt != null -> "completed"
  else -> record.status
}

private fun decodeStepsForTest(
  repository: InMemoryWorkflowStateRepository,
  workflowId: String,
): List<Pair<String, String>> {
  val stepsJson = requireNotNull(repository.getFeatureTaskRuntimeWorkflow(workflowId)).stepsJson
  val element = JsonSupport.json.parseToJsonElement(stepsJson)
  return (JsonSupport.jsonElementToValue(element) as List<*>).map { raw ->
    val item = raw as Map<*, *>
    item["step_id"].toString() to item["status"].toString()
  }
}

private fun stepStatusFor(repository: InMemoryWorkflowStateRepository, workflowId: String, stepId: String): String =
  decodeStepsForTest(repository, workflowId).first { it.first == stepId }.second

private fun assertRuntimeWorkflowRow(
  repository: InMemoryWorkflowStateRepository,
  workflowId: String,
  currentStepId: String,
  workflowStatus: String,
) {
  val row = requireNotNull(repository.getFeatureTaskRuntimeWorkflow(workflowId))
  assertEquals(currentStepId, row.currentStepId)
  assertEquals(workflowStatus, row.workflowStatus)
}

private fun testWorkflowService(
  database: DatabaseSessionFactory,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
): WorkflowService = WorkflowService(
  database,
  gitOperations,
  FileSystemDecompositionManifestFileStore(),
  WorkflowSnapshotValidatorInfraAdapter(),
  DecompositionManifestValidatorAdapter(),
)

private fun loadTestDecompositionManifest(path: Path) =
  loadDecompositionManifest(path, FileSystemDecompositionManifestFileStore(), DecompositionManifestValidatorAdapter())

private class InMemoryWorkflowStateRepository(
  private val implementSessionSummary: FeatureImplementSessionSummary? = null,
  private val verifySessionSummary: FeatureVerifySessionSummary? = null,
) : WorkflowStateRepository {
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()
  private val verifyRows = linkedMapOf<String, WorkflowStateRecord>()
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  var failNextImplementSave: Boolean = false

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    if (failNextImplementSave) {
      failNextImplementSave = false
      error("save failed")
    }
    implementRows[row.workflowId] = row
  }

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    verifyRows[row.workflowId] = row
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implementRows[workflowId]

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = verifyRows[workflowId]

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    implementRows.values.toList().asReversed().take(limit)

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> =
    verifyRows.values.toList().asReversed().take(limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = listFeatureImplementWorkflows(1).firstOrNull()

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = listFeatureVerifyWorkflows(1).firstOrNull()

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? =
    implementSessionSummary?.takeIf { it.sessionId == sessionId }

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? =
    verifySessionSummary?.takeIf { it.sessionId == sessionId }

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()
}

private class FakeWorkflowGitOperations(
  private val commitSha: String = "commit-sha",
  private val commitError: String = "",
) : WorkflowGitOperations {
  val checkouts = mutableListOf<String>()
  val baseValidations = mutableListOf<String>()
  val commits = mutableListOf<String>()

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkouts += "$branch@${baseBranch.orEmpty()}"
    return WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = checkouts.lastOrNull()?.substringBefore("@").orEmpty())

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    commits += message
    if (commitError.isNotBlank()) {
      return WorkflowGitOperationResult(status = "error", error = commitError)
    }
    return WorkflowGitOperationResult(status = "ok", value = commitSha)
  }

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = commitSha)

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    baseValidations += "$branch@$expectedBaseBranch"
    return WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
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
