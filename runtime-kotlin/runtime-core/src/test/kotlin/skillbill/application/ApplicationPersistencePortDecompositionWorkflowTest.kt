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

class ApplicationPersistencePortDecompositionWorkflowTest {
  fun `workflow service writes decomposition manifest when implement plan decomposes`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
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
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val updated = service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
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

    val persisted = service.get(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null) as WorkflowGetResult.Ok
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
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    // Durable save still precedes decomposition-manifest projection; failure must abort on the
    // TASK_RUNTIME save path (saveFeatureTaskRuntimeWorkflow), not the retired prose implement save.
    workflowRepository.failNextRuntimeSave = true
    assertFailsWith<IllegalStateException> {
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
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
    assertEquals("Pending", statusLine(subtaskSpec))

    markDecompositionSubtaskSkipped(service, workflowId, subtaskSpec)

    val skippedManifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals("skipped", skippedManifest.subtasks.single().status)
    assertEquals("complete", skippedManifest.currentSubtaskIntent.action)
    assertEquals("Pending", statusLine(subtaskSpec))
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
    val service = testWorkflowService(database, FakeWorkflowGitOperations())
    val workflowId = createDecompositionWorkflow(service, parentSpec, subtaskSpec)

    // requiredArtifactsByStep[validate]=[plan,audit]; seed completed phase records so canResume
    // is true. assessment.spec_path remains decomposition metadata only
    // (DecompositionManifestRuntimeState), never a resume-gate satisfier.
    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = "blocked",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "blocked", "attempt_count" to 1)),
        artifactsPatch =
        mapOf(
          "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to completedPhaseRecords("plan", "audit"),
          "blocked_reason" to "Validation paused.",
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val subtask = manifest.subtasks.single()
    assertEquals("reopened", continued.view.continueStatus)
    assertEquals("in_progress", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals("validate", subtask.lastResumableStep)
    assertEquals("Pending", statusLine(subtaskSpec))
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

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
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
      WorkflowFamilyKind.TASK_RUNTIME,
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(
      WorkflowFamilyKind.TASK_RUNTIME,
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
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

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    val parent =
      service.get(WorkflowFamilyKind.TASK_RUNTIME, parentWorkflowId, dbOverride = null) as WorkflowGetResult.Ok
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
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

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(
      WorkflowFamilyKind.TASK_RUNTIME,
      "SKILL-51",
      subtaskId = 1,
      dbOverride = null,
    ) as WorkflowContinueResult.DecompositionSubtaskOutcome
    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))

    assertEquals(1, continued.subtaskId)
    assertEquals("complete", continued.outcome.status)
    // FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR — lastResumableStep tracks runtime step ids.
    assertEquals("pr", continued.outcome.lastResumableStep)
    assertEquals("pending", manifest.subtasks.first { it.id == 2 }.status)
  }

  @Test
  fun `workflow service completes all subtasks without mutating specs`() {
    val tempDir = Files.createTempDirectory("skillbill-app-decomposition-complete")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskOne = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val subtaskTwo = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
    writeSpecs(parentSpec, subtaskOne, subtaskTwo)
    val workflowRepository = InMemoryWorkflowStateRepository()
    val git = FakeWorkflowGitOperations(commitSha = "abc123")
    val service = testWorkflowService(FakeDatabaseSessionFactory(workflows = workflowRepository), git)
    createDecompositionWorkflow(service, parentSpec, subtaskOne, subtaskTwo)
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)
    val second = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, second.view.resume.snapshot.workflowId, subtaskTwo)

    val done = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionDone

    val manifest = loadTestDecompositionManifest(parentSpec.parent.resolve("decomposition-manifest.yaml"))
    assertEquals("complete", done.decompositionStatus)
    assertEquals("complete", manifest.status)
    assertTrue(manifest.subtasks.all { it.status == "complete" })
    assertTrue(manifest.subtasks.all { it.commitSha == null })
    assertEquals(listOf("SKILL-51 subtask 1: foundation", "SKILL-51 subtask 2: runtime"), git.commits)
    assertEquals("Pending", statusLine(parentSpec))
    assertEquals("Pending", statusSection(parentSpec))
    assertEquals("Pending", statusLine(subtaskOne))
    assertEquals("Pending", statusLine(subtaskTwo))
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    markDecompositionSubtaskComplete(service, first.view.resume.snapshot.workflowId, subtaskOne)

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
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

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)

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

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
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
    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard
    val subtaskWorkflowId = first.view.resume.snapshot.workflowId
    // requiredArtifactsByStep[validate]=[plan,audit] via FeatureTaskRuntimeRequiredArtifactPresenceResolver.
    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = subtaskWorkflowId,
        workflowStatus = "running",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to completedPhaseRecords("plan", "audit"),
          "validation_result" to mapOf("passed" to false),
        ),
      ),
      dbOverride = null,
    )

    val continued = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51", dbOverride = null)
      as WorkflowContinueResult.DecompositionStandard

    assertEquals("already_running", continued.view.continueStatus)
    assertEquals(subtaskWorkflowId, continued.view.resume.snapshot.workflowId)
    assertEquals("validate", continued.view.continueStepId)
    assertEquals(1, continued.decompositionSubtaskId)
  }
}
