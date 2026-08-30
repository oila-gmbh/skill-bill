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

class ApplicationPersistencePortGoalTest {
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

  @Test
  fun `goal planning preparation is a separate port unreachable from standalone feature-task persistence`() {
    val goalPlanningPort = GoalPlanningPreparationRepository::class.java
    val workflowStatePort = WorkflowStateRepository::class.java

    assertTrue(
      goalPlanningPort !in workflowStatePort.interfaces,
      "GoalPlanningPreparationRepository must remain a separate port; WorkflowStateRepository must not compose it.",
    )
    assertTrue(
      workflowStatePort !in goalPlanningPort.interfaces,
      "GoalPlanningPreparationRepository must not inherit the standalone feature-task port.",
    )
    val standaloneMethods = workflowStatePort.declaredMethods.map { it.name }.toSet()
    val goalPlanningMethodNames = listOf(
      "markPrepared",
      "findByGoalAndSubtask",
      "listPreparedByGoalOrdered",
      "preparedCount",
      "firstMissingOrIncompleteSubtask",
    )
    goalPlanningMethodNames.forEach { methodName ->
      assertTrue(
        methodName !in standaloneMethods,
        "Standalone WorkflowStateRepository must not expose goal-planning method '$methodName'.",
      )
    }
    val sqlTypedMembers = goalPlanningPort.declaredMethods.filter { function ->
      function.returnType.name.startsWith("java.sql") ||
        function.parameterTypes.any { type -> type.name.startsWith("java.sql") }
    }
    assertTrue(
      sqlTypedMembers.isEmpty(),
      "GoalPlanningPreparationRepository must not expose java.sql types: ${sqlTypedMembers.map { it.name }}",
    )
    assertTrue(
      goalPlanningPort.isAssignableFrom(EmptyGoalPlanningPreparationRepository::class.java),
      "EmptyGoalPlanningPreparationRepository must satisfy the goal-planning port for test fakes.",
    )
    val unitOfWorkClass = UnitOfWork::class.java
    val unitOfWorkGetter =
      unitOfWorkClass.declaredMethods.single { method -> method.name == "getGoalPlanningPreparations" }
    assertTrue(
      unitOfWorkGetter.returnType == goalPlanningPort,
      "UnitOfWork.goalPlanningPreparations must be typed as the dedicated GoalPlanningPreparationRepository port.",
    )
  }
}
