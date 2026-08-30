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

class ApplicationPersistencePortWorkflowTest {
  @Test
  fun `workflow service owns implement rows list resume and continuation through ports`() {
    // Resume gate judges upstream presence from completed private phase records
    // (FeatureTaskRuntimeRequiredArtifactPresenceResolver / requiredArtifactsByStep[implement]=[plan]),
    // matching WorkflowCompactContinuationTest — not top-level plan/preplan_digest maps.
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val updated = service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "preplan_digest" to mapOf("ok" to true),
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
            "plan" to completedPhaseRecord("plan", outputArtifact = """{"task_count":1}"""),
          ),
        ),
      ),
      dbOverride = null,
    ) as WorkflowUpdateResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null)
    val latest = service.latest(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null) as WorkflowLatestResult.Ok
    val resumed =
      service.resume(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null) as WorkflowResumeResult.Ok
    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    assertEquals(listOf("transaction", "transaction", "read", "read", "read", "transaction"), database.calls)
    assertEquals("blocked", updated.acknowledgement.workflowStatus)
    assertEquals(1, listed.workflowCount)
    assertEquals(workflowId, latest.summary.workflowId)
    assertEquals(emptyList(), resumed.resume.missingArtifacts)
    assertEquals("reopened", continued.view.continueStatus)
  }

  @Test
  fun `workflow service owns task runtime rows through ports for save load list latest`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val first = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val second = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-002", dbOverride = null)
      as WorkflowOpenResult.Ok

    val got = service.get(WorkflowFamilyKind.TASK_RUNTIME, first.workflowId, dbOverride = null)
      as WorkflowGetResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null)
    val latest = service.latest(WorkflowFamilyKind.TASK_RUNTIME, dbOverride = null) as WorkflowLatestResult.Ok

    assertEquals("bill-feature-task", got.snapshot.workflowName)
    assertEquals("runtime", got.snapshot.mode)
    assertEquals(2, listed.workflowCount)
    assertEquals(second.workflowId, latest.summary.workflowId)
    assertEquals(0, service.list(WorkflowFamilyKind.VERIFY, dbOverride = null).workflowCount)
  }

  @Test
  fun `task runtime recorder mints timestamps and persists per-phase record and append-only ledger`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.START))
    assertTrue(recorder.recordPlanPhase(workflowId, status = "running", finished = false))
    assertTrue(
      recorder.recordPlanPhase(
        workflowId,
        status = "completed",
        finished = true,
        outputArtifact = """{"contract_version":"0.2"}""",
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
    assertEquals("""{"contract_version":"0.2"}""", planRecord["output_artifact"])

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(listOf(0, 1), sequences)
    assertEquals(sequences.sorted(), sequences)
    assertEquals(listOf("start", "complete"), ledger.map { it["action"] })
  }

  @Test
  fun `recording a briefing persists the delivered projection under its own artifact key`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)

    assertTrue(recorder.recordPhaseBriefing(workflowId, handoffBriefing()))

    val artifacts = decodeArtifactsForTest(
      requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    assertTrue(artifacts.containsKey(FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY))
    val delivered = requireNotNull(recorder.loadDeliveredProjections(workflowId))["implement"]
    assertEquals(handoffBriefing().handoffEnvelope, requireNotNull(delivered).envelope)
    assertEquals(1, delivered.iteration)

    // A second delivery to the same consumer is a new iteration, not a silent overwrite of history.
    assertTrue(recorder.recordPhaseBriefing(workflowId, handoffBriefing()))
    assertEquals(2, requireNotNull(recorder.loadDeliveredProjections(workflowId))["implement"]?.iteration)
    val afterSecondDelivery = decodeArtifactsForTest(
      requireNotNull(workflowRepository.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )

    @Suppress("UNCHECKED_CAST")
    val deliveredHistory =
      afterSecondDelivery[FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY] as Map<String, Any?>
    assertEquals(2, deliveredHistory.size, "distinct producer iterations must remain independently durable")
    assertTrue(
      deliveredHistory.keys.all { "|plan#1|" in it },
      "durable selection keys must carry the normalized source producer identity",
    )
  }

  @Test
  fun `an unsupported envelope contract version is rejected at the briefing write seam`() {
    val database = FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository())
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      recorder.recordPhaseBriefing(
        workflowId,
        handoffBriefing(envelope = handoffEnvelope().copy(contractVersion = "9.9")),
      )
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
    assertEquals("implement", error.consumerPhaseId)
  }

  @Test
  fun `projection rejection emits content free classified telemetry before briefing delivery`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val telemetry = RecordingProjectionLifecycleTelemetryRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository, lifecycleTelemetry = telemetry)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    val rejection = InvalidFeatureTaskRuntimeHandoffProjectionError(
      workflowId = workflowId,
      consumerPhaseId = "implement",
      projectionName = "plan_receipt",
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
      projectionContractVersion = "0.2",
      failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION,
      reason = "repository checkpoint differs",
    )

    assertTrue(recorder.recordProjectionRejection(workflowId, "implement", rejection, "checkpoint-2"))

    val measurement = telemetry.projectionMeasurements.single()
    assertEquals(FeatureTaskRuntimeProjectionFailureClassification.STALE_CHECKPOINT, measurement.failureClassification)
    assertEquals(0, measurement.deliveredProjectionUtf8Bytes)
    assertEquals(0, measurement.privateEvidenceUtf8Bytes)
  }

  @Test
  fun `an undeclared wire key on a durable envelope is rejected at the briefing read seam`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    assertTrue(recorder.recordPhaseBriefing(workflowId, handoffBriefing()))

    corruptDurableEnvelope(workflowRepository, workflowId) { envelope ->
      envelope + ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw payload"))
    }

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      recorder.loadPhaseBriefings(workflowId)
    }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
  }

  @Test
  fun `an unsupported contract version on a durable envelope is rejected at the briefing read seam`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    assertTrue(recorder.recordPhaseBriefing(workflowId, handoffBriefing()))

    corruptDurableEnvelope(workflowRepository, workflowId) { it + ("contract_version" to "9.9") }

    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> { recorder.loadPhaseBriefings(workflowId) }
  }

  @Test
  fun `operator blocked-phase retry stays active through launch and clears on terminal transition`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)
    val workflowId = (
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
        as WorkflowOpenResult.Ok
      ).workflowId
    val reason = "Use the operator-approved fresh-process isolation boundary."
    recorder.recordRuntimePhase(
      workflowId,
      phaseId = "implement",
      status = "blocked",
      finished = false,
      blockedReason = "native adapter unavailable",
    )

    val retried = service.retryBlockedFeatureTaskRuntimePhase(workflowId, "implement", reason)

    assertTrue(retried is WorkflowUpdateResult.Ok)
    assertEquals(reason, recorder.loadOperatorBlockRetry(workflowId)?.reason)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.START,
        phaseId = "implement",
        attemptCount = 1,
        resolvedAgentId = "codex",
      ),
    )
    assertEquals(reason, recorder.loadOperatorBlockRetry(workflowId)?.reason)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        phaseId = "implement",
        attemptCount = 1,
        resolvedAgentId = "codex",
      ),
    )
    assertEquals(null, recorder.loadOperatorBlockRetry(workflowId))
  }

  @Test
  fun `operator blocked-phase retry reopens the authoritative goal manifest`() {
    val fixture = blockedGoalChildRetryFixture()

    val retried = fixture.service.retryBlockedFeatureTaskRuntimePhase(
      fixture.childWorkflowId,
      "implement",
      "Use the operator-approved fresh-process isolation boundary.",
    )

    assertTrue(retried is WorkflowUpdateResult.Ok)
    val manifest = loadTestDecompositionManifest(fixture.manifestPath)
    assertEquals("in_progress", manifest.status)
    assertEquals("resume", manifest.currentSubtaskIntent.action)
    assertEquals(1, manifest.currentSubtaskIntent.subtaskId)
    assertEquals("in_progress", manifest.subtasks.single().status)
    assertEquals(fixture.childWorkflowId, manifest.subtasks.single().workflowId)
    assertEquals(null, manifest.subtasks.single().blockedReason)
    assertEquals("implement", manifest.subtasks.single().lastResumableStep)
    val parent = fixture.service.get(
      WorkflowFamilyKind.TASK_RUNTIME,
      fixture.parentWorkflowId,
    ) as WorkflowGetResult.Ok
    val parentRuntime = parent.snapshot.artifacts["decomposition_runtime"] as Map<*, *>
    assertEquals("in_progress", parentRuntime["status"])
  }

  @Test
  fun `task runtime recorder advances shared steps in lockstep with per-phase records`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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
    val recorder = testPhaseRecorder(database)

    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
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

}
