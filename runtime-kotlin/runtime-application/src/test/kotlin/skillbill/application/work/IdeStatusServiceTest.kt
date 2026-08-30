package skillbill.application.work

import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.goalrunner.goalRunnerStatusServiceDeps
import skillbill.application.goalrunner.testGoalRunnerStatusService
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.application.idestatus.model.IdeStatusFreshness
import skillbill.application.idestatus.model.IdeStatusLifecycleState
import skillbill.application.idestatus.model.IdeStatusProblemCode
import skillbill.application.idestatus.model.IdeStatusRequest
import skillbill.application.idestatus.model.IdeStatusResult
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.EmptyFeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.learning.LearningRepository
import skillbill.ports.review.ReviewRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.idestatus.IdeStatusValidator
import skillbill.workflow.idestatus.NoopIdeStatusValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdeStatusServiceTest {

  @Test
  fun `invalid repository root yields typed invalid_repository_input without writes`() {
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val service = service(database)
    val missing = Files.createTempDirectory("ide-status-missing").resolve("no-repo")

    val result = service.status(IdeStatusRequest(repoRoot = missing.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(1, result.exitCode)
    assertEquals(IdeStatusProblemCode.INVALID_REPOSITORY_INPUT, result.snapshot.problem?.code)
    assertEquals(IdeStatusLifecycleState.IDLE, result.snapshot.lifecycleState)
    assertEquals(0, database.writeCalls)
    assertEquals(0, database.readCalls)
  }

  @Test
  fun `absent database yields typed problem and never opens a session`() {
    val fixture = gitRepoFixture("ide-status-absent-db")
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates(), exists = false)
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.ABSENT_DATABASE, result.snapshot.problem?.code)
    assertEquals(goalRepositoryIdentity(fixture), result.snapshot.repositoryIdentity)
    assertEquals(0, database.readCalls)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `empty work list yields no_matching_work via database read only`() {
    val fixture = gitRepoFixture("ide-status-idle")
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(1, database.readCalls)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `a genuinely empty repository still yields the unchanged no_matching_work snapshot`() {
    val fixture = gitRepoFixture("ide-status-no-matching-work-shape")
    val service = service(TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates()))

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(
      "No recent Skill Bill work for branch 'feat/SKILL-148-fixture'.",
      result.snapshot.problem?.message,
    )
    assertEquals(result.snapshot.problem?.message, result.snapshot.summary)
    assertEquals(IdeStatusLifecycleState.IDLE, result.snapshot.lifecycleState)
    assertEquals(IdeStatusFreshness.FRESH, result.snapshot.freshness)
    assertEquals("none", result.snapshot.currentStep.id)
    assertEquals("No matching work", result.snapshot.currentStep.label)
    assertEquals(ideStatusObservedAt, result.snapshot.updatedAt)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `a typed workflow-schema failure during collection surfaces as an incompatible record`() {
    val fixture = gitRepoFixture("ide-status-orphaned-workflow-row")
    val database = TrackingDatabase(
      work = listOf(workItem("w-orphan", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z")),
      workflows = OrphanedIdentityWorkflowStates("Feature-task identity 'w-orphan' has no workflow row."),
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(IdeStatusProblemCode.INCOMPATIBLE_RECORD, result.snapshot.problem?.code)
    assertEquals("Feature-task identity 'w-orphan' has no workflow row.", result.snapshot.problem?.message)
    assertEquals(1, result.exitCode)
  }

  @Test
  fun `repository isolation ignores work bound to another repository identity`() {
    val fixture = gitRepoFixture("ide-status-isolation")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-foreign", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = "w-foreign",
        normalizedIssueKey = "SKILL-148",
        repositoryIdentity = "repo-root-realpath-v1:/other-repo",
        governedSpecPath = "spec.md",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ),
    )
    val database = TrackingDatabase(
      work = listOf(workItem("w-foreign", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `active runtime work outranks terminal competitor for the same repository`() {
    val fixture = gitRepoFixture("ide-status-precedence")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z", currentStep = "implement"))
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-terminal", "2026-08-06T11:00:00Z", currentStep = "pr"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-terminal", identity))
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-terminal", WorkItemKind.FEATURE_TASK_RUNTIME, "completed", "2026-08-06T11:00:00Z"),
        workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-active", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
        assertEquals("preplan", result.snapshot.currentStep.id)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), result.snapshot.startedAt)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `goal-child runtime is suppressed when an authoritative feature-goal exists for the issue`() {
    val fixture = gitRepoFixture("ide-status-goal-child")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-child", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z"),
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T09:00:00Z"),
      ),
      workflows = workflows,
      controls = controls,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `runtime current_model reports the model of the phase reported as current_step`() {
    val fixture = gitRepoFixture("ide-status-current-model")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-model", "2026-08-06T10:00:00Z").copy(
                artifactsJson = phaseRecordsArtifactsJson(
          "preplan" to phaseRecordWire("preplan", "completed", null),
          "plan" to phaseRecordWire("plan", "completed", "plan-model"),
          "implement" to phaseRecordWire(
            "implement",
            "running",
            "claude-opus-4-8",
            options = PhaseRecordOptions(effort = "high"),
          ),
        ),
      ),
    )
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-model", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-model", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals("claude-opus-4-8", result.snapshot.currentModel?.model)
    assertEquals("high", result.snapshot.currentModel?.effort)
  }

  @Test
  fun `runtime current_model is omitted when the current phase has no recorded model`() {
    val fixture = gitRepoFixture("ide-status-current-model-absent")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-nomodel", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-nomodel", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-nomodel", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertNull(result.snapshot.currentModel)
  }

  @Test
  fun `goal current_model comes from the launched child's current phase and is omitted without one`() {
    val fixture = gitRepoFixture("ide-status-goal-current-model")
    val identity = goalRepositoryIdentity(fixture)
    val childStarted = Instant.parse("2026-08-06T09:15:00Z")
    val database = goalWithLaunchedChildDatabase(
      identity,
      childStarted,
      childArtifactsJson = phaseRecordsArtifactsJson(
        "preplan" to phaseRecordWire("preplan", "completed", null),
        "plan" to phaseRecordWire("plan", "completed", null),
        "implement" to phaseRecordWire("implement", "running", "claude-opus-4-8[effort=high]"),
      ),
    )
    val withChild = service(
      database,
      manifestStore = StubGoalManifestStore(goalManifestState(fixture, identity, childWorkflowId = "w-child")),
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

            val withoutChild = service(database)
      .status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals("claude-opus-4-8[effort=high]", withChild.snapshot.currentModel?.model)
    assertNull(withChild.snapshot.currentModel?.effort)
            assertEquals("implement", withChild.snapshot.currentModel?.phaseId)
            assertEquals("goal-1", withoutChild.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, withoutChild.snapshot.workflowFamily)
    assertNull(withoutChild.snapshot.currentModel)
  }

  @Test
  fun `a finished run reports no current_model for the completed phase its step falls back to`() {
    val fixture = gitRepoFixture("ide-status-current-model-settled")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
                val allCompleted = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.map { phaseId ->
      phaseId to phaseRecordWire(phaseId, "completed", "claude-opus-4-8".takeIf { phaseId == "pr" })
    }
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-settled", "2026-08-06T10:00:00Z", currentStep = "pr")
        .copy(artifactsJson = phaseRecordsArtifactsJson(*allCompleted.toTypedArray())),
    )
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-settled", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-settled", WorkItemKind.FEATURE_TASK_RUNTIME, "completed", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals("w-settled", result.snapshot.workflowId)
    assertEquals("pr", result.snapshot.currentStep.id)
    assertNull(result.snapshot.currentModel)
  }

  @Test
  fun `a goal whose child status read fails schema validation keeps its status and omits only current_model`() {
    val fixture = gitRepoFixture("ide-status-goal-child-schema-invalid")
    val identity = goalRepositoryIdentity(fixture)
                val database = goalWithLaunchedChildDatabase(
      identity,
      Instant.parse("2026-08-06T09:15:00Z"),
      childArtifactsJson = JsonSupport.mapToJsonString(
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
            "implement" to phaseRecordWire("implement", "running", "claude-opus-4-8"),
          ),
          FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY to "not-a-map",
        ),
      ),
    )

    val result = service(
      database,
      manifestStore = StubGoalManifestStore(goalManifestState(fixture, identity, childWorkflowId = "w-child")),
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

                        assertEquals("goal-1", result.snapshot.workflowId)
    assertNull(result.snapshot.problem)
    assertNotNull(result.snapshot.progress)
    assertNull(result.snapshot.currentModel)
    assertNull(result.snapshot.currentPhaseExecution)
  }

  @Test
  fun `runtime current_phase_execution reports the current phase only and never a completed neighbour`() {
    val fixture = gitRepoFixture("ide-status-current-phase-execution")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-exec", "2026-08-06T10:00:00Z", currentStep = "validate").copy(
        artifactsJson = phaseRecordsArtifactsJson(
          "preplan" to phaseRecordWire("preplan", "completed", null),
          "plan" to phaseRecordWire("plan", "completed", null),
          "implement" to phaseRecordWire("implement", "completed", null),
          "audit" to phaseRecordWire("audit", "completed", null),
                    "review" to phaseRecordWire(
            "review",
            "completed",
            null,
            options = PhaseRecordOptions(reviewPassNumber = 3),
          ),
          "verify_findings" to phaseRecordWire("verify_findings", "completed", null),
          "validate" to phaseRecordWire(
            "validate",
            "running",
            null,
            options = PhaseRecordOptions(attemptCount = 2),
          ),
        ),
      ),
    )
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-exec", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-exec", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))
    val execution = requireNotNull(result.snapshot.currentPhaseExecution)

    assertEquals("validate", result.snapshot.currentStep.id)
    assertEquals("validate", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.ATTEMPT, execution.kind)
    assertEquals(2, execution.count)
    assertNull(execution.total)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("planning"))
  }

  @Test
  fun `runtime review current_phase_execution uses durable review pass number`() {
    val fixture = gitRepoFixture("ide-status-review-pass")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-review", "2026-08-06T10:00:00Z", currentStep = "review").copy(
        artifactsJson = phaseRecordsArtifactsJson(
          "preplan" to phaseRecordWire("preplan", "completed", null),
          "plan" to phaseRecordWire("plan", "completed", null),
          "implement" to phaseRecordWire("implement", "completed", null),
          "audit" to phaseRecordWire("audit", "completed", null),
          "review" to phaseRecordWire(
            "review",
            "running",
            null,
            options = PhaseRecordOptions(
              attemptCount = 4,
              reviewPassNumber = 2,
            ),
          ),
        ),
      ),
    )
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-review", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-review", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))
    val execution = requireNotNull(result.snapshot.currentPhaseExecution)

    assertEquals("review", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(2, execution.count)
  }
}
