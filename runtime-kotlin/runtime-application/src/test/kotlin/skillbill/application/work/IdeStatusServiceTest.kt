package skillbill.application.work

import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.application.model.IdeStatusFreshness
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusProblemCode
import skillbill.application.model.IdeStatusRequest
import skillbill.application.model.IdeStatusResult
import skillbill.application.model.IdeStatusWorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.EmptyFeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
import skillbill.ports.persistence.EmptyGoalRunnerControlRepository
import skillbill.ports.persistence.GoalRunnerControlRepository
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.workflow.IdeStatusValidator
import skillbill.workflow.NoopIdeStatusValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
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

/**
 * SKILL-148 Subtask 1: application-layer coverage for read-only IDE status selection,
 * repository isolation, and typed problem outcomes.
 */
@Suppress("LargeClass") // cohesive service suite spanning selection, isolation, and typed problem outcomes
class IdeStatusServiceTest {
  private val observedAt: Instant = Instant.parse("2026-08-06T12:00:00Z")
  private val clock: Clock = Clock.fixed(observedAt, ZoneOffset.UTC)

  @Test
  fun `invalid repository root yields typed invalid_repository_input without writes`() {
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val service = service(database)
    val missing = Files.createTempDirectory("ide-status-missing").resolve("no-repo")

    val result = service.status(IdeStatusRequest(repoRoot = missing.toString(), observedAt = observedAt))

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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(1, database.readCalls)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `a genuinely empty repository still yields the unchanged no_matching_work snapshot`() {
    val fixture = gitRepoFixture("ide-status-no-matching-work-shape")
    val service = service(TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates()))

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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
    assertEquals(observedAt, result.snapshot.updatedAt)
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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-active", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    // A minimal runtime row (no per-phase records) projects its first pending phase as current.
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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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
        // The neighbouring plan phase carries a DIFFERENT model: reporting it would be the bug.
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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

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
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    // The goal's own currentStep can be a planning label, never a phase-record key; with no child
    // workflow id there is nothing to resolve and the field must be absent rather than mismatched.
    val withoutChild = service(database)
      .status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("claude-opus-4-8[effort=high]", withChild.snapshot.currentModel?.model)
    assertNull(withChild.snapshot.currentModel?.effort)
    // The goal's current_step is a goal-level label, so the phase id is the only thing in the
    // payload that says which phase the model belongs to.
    assertEquals("implement", withChild.snapshot.currentModel?.phaseId)
    // Anchored so the null is attributable to the absent child id rather than to a snapshot that
    // carries no goal projection at all — the guard could be deleted and this would still hold.
    assertEquals("goal-1", withoutChild.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, withoutChild.snapshot.workflowFamily)
    assertNull(withoutChild.snapshot.currentModel)
  }

  @Test
  fun `a finished run reports no current_model for the completed phase its step falls back to`() {
    val fixture = gitRepoFixture("ide-status-current-model-settled")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    // Every phase terminal, so the projection resolves no current phase and current_step falls back
    // to the workflow row's `pr`. That phase completed carrying a model — history, not the model in
    // force — so a finished run must not report it as current.
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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("w-settled", result.snapshot.workflowId)
    assertEquals("pr", result.snapshot.currentStep.id)
    assertNull(result.snapshot.currentModel)
  }

  @Test
  fun `a goal whose child status read fails schema validation keeps its status and omits only current_model`() {
    val fixture = gitRepoFixture("ide-status-goal-child-schema-invalid")
    val identity = goalRepositoryIdentity(fixture)
    // Run invariants that do not decode to a map. The goal's own agent attribution reads the child's
    // phase records and ledger, which stay valid here; only the nested child status projection reads
    // this artifact, so the failure lands exactly where resolving the model does and nowhere else.
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
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    // Resolving the model is a nested read of a *different* workflow's rows. Letting its failure
    // escape would downgrade the whole goal snapshot to one schema_incompatible record, trading
    // lifecycle, progress and pause state for an optional field.
    // A degraded snapshot carries no workflow id and no progress, only the problem — so these three
    // together prove the goal reading survived rather than collapsing into an incompatible record.
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
          // Completed review still carries pass 3 — leaking it would be the bug under test.
          "review" to phaseRecordWire(
            "review",
            "completed",
            null,
            options = PhaseRecordOptions(reviewPassNumber = 3),
          ),
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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))
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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))
    val execution = requireNotNull(result.snapshot.currentPhaseExecution)

    assertEquals("review", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(2, execution.count)
  }

  @Test
  fun `goal mid-planning keeps planning and omits current_phase_execution`() {
    val fixture = gitRepoFixture("ide-status-planning-no-execution")
    val identity = goalRepositoryIdentity(fixture)
    val result = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child"),
        planning = planningSnapshot(GoalPlanningStatusState.PARTIALLY_PLANNED),
      ),
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("planning", result.snapshot.currentStep.id)
    assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, result.snapshot.planning?.state)
    assertNull(result.snapshot.currentPhaseExecution)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("current_phase_execution"))
  }

  @Test
  fun `goal with launched child projects child current_phase_execution`() {
    val fixture = gitRepoFixture("ide-status-goal-child-execution")
    val identity = goalRepositoryIdentity(fixture)
    val database = goalWithLaunchedChildDatabase(
      identity,
      Instant.parse("2026-08-06T09:15:00Z"),
      childArtifactsJson = phaseRecordsArtifactsJson(
        "preplan" to phaseRecordWire("preplan", "completed", null),
        "plan" to phaseRecordWire("plan", "completed", null),
        "implement" to phaseRecordWire("implement", "completed", null),
        "audit" to phaseRecordWire("audit", "completed", null),
        "review" to phaseRecordWire(
          "review",
          "running",
          null,
          options = PhaseRecordOptions(reviewPassNumber = 2),
        ),
      ),
    )
    val result = service(
      database,
      manifestStore = StubGoalManifestStore(goalManifestState(fixture, identity, childWorkflowId = "w-child")),
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    val execution = requireNotNull(result.snapshot.currentPhaseExecution)
    assertEquals("review", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(2, execution.count)
  }

  @Test
  fun `started_at stays stable across two polls for the same durable runtime work`() {
    val fixture = gitRepoFixture("ide-status-stable-start")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-stable", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-stable", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-stable", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val first = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))
    val second = service.status(
      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt.plusSeconds(60)),
    )

    assertEquals(first.snapshot.startedAt, second.snapshot.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), first.snapshot.startedAt)
  }

  @Test
  fun `unbound verify work is excluded without same-repo issue correlation`() {
    val fixture = gitRepoFixture("ide-status-verify-unbound")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    val database = TrackingDatabase(
      work = listOf(workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work for another repo issue correlation is not selected`() {
    val fixture = gitRepoFixture("ide-status-verify-other")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-foreign", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-foreign", "repo-root-realpath-v1:/other-repo"),
    )
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
        workItem("w-foreign", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work is included when issue correlates to same-repo feature-task identity`() {
    val fixture = gitRepoFixture("ide-status-verify-correlated")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-runtime", "2026-08-06T09:00:00Z", currentStep = "pr"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-runtime", identity))
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
        workItem("w-runtime", WorkItemKind.FEATURE_TASK_RUNTIME, "completed", "2026-08-06T09:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-verify", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_VERIFY, result.snapshot.workflowFamily)
  }

  @Test
  fun `branch scoping hides work whose issue key is not in the checked-out branch`() {
    val fixture = gitRepoFixture("ide-status-branch-scope", branch = "feat/OTHER-9-unrelated")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals("No recent Skill Bill work for branch 'feat/OTHER-9-unrelated'.", result.snapshot.summary)
  }

  @Test
  fun `branch scoping requires a whole issue-key token, not a prefix hit`() {
    // SKILL-14 must not match the SKILL-148 fixture work: '8' continues the token.
    val fixture = gitRepoFixture("ide-status-branch-token", branch = "feat/SKILL-14-prefix")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
  }

  @Test
  fun `unresolvable checkout disables branch scoping instead of hiding work`() {
    val fixture = gitRepoFixture("ide-status-branch-detached", branch = null)
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `protected base branch disables scoping so pre-branch work stays visible`() {
    // A goal is 'running' from goal start, but only acquires feat/SKILL-148-... at the
    // create_branch step of its first subtask launch. Scoping 'main' by issue-key name
    // would report idle for that whole window.
    val fixture = gitRepoFixture("ide-status-branch-protected", branch = "main")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `running goal row with every subtask settled projects terminal complete`() {
    val fixture = gitRepoFixture("ide-status-goal-settled")
    val identity = goalRepositoryIdentity(fixture)
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        completedGoalManifestState(fixture, identity),
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState)
    assertEquals("done", result.snapshot.currentStep.id)
    assertEquals("Complete", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is complete.", result.snapshot.summary)
  }

  @Test
  fun `blocked or failed goal row with every subtask settled projects terminal complete`() {
    // Finalization can die after the last subtask and leave the parent work-list row
    // blocked/failed. goal status already reports complete; IDE status must not keep
    // prompting as blocked.
    listOf("blocked", "failed").forEach { stuckState ->
      val fixture = gitRepoFixture("ide-status-goal-settled-$stuckState")
      val identity = goalRepositoryIdentity(fixture)
      val service = service(
        goalOnlyDatabase(goalState = stuckState),
        manifestStore = StubGoalManifestStore(
          completedGoalManifestState(fixture, identity),
        ),
      )

      val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

      assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState, stuckState)
      assertEquals("Goal SKILL-148 is complete.", result.snapshot.summary, stuckState)
    }
  }

  private fun completedGoalManifestState(fixture: Path, identity: String): GoalRunnerManifestState {
    val base = goalManifestState(fixture, identity, childWorkflowId = "w-child")
    return base.copy(
      manifest = base.manifest.copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
        subtasks = base.manifest.subtasks.map { it.copy(status = "complete", lastResumableStep = null) },
      ),
    )
  }

  @Test
  fun `goal current_subtask started_at comes from durable launched child WorkItem`() {
    val fixture = gitRepoFixture("ide-status-goal-subtask-start")
    val identity = goalRepositoryIdentity(fixture)
    val childStarted = Instant.parse("2026-08-06T09:15:00Z")
    val database = goalWithLaunchedChildDatabase(identity, childStarted)
    val service = service(
      database,
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child"),
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals("2", result.snapshot.currentSubtask?.id)
    assertEquals(childStarted, result.snapshot.currentSubtask?.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), result.snapshot.startedAt)
  }

  @Test
  fun `feature-goal freshness follows the newest same-repo child workflow write`() {
    val fixture = gitRepoFixture("ide-status-goal-freshness")
    val identity = goalRepositoryIdentity(fixture)
    // The goal row's state_entered_at is 2h stale; the running child wrote 15m ago.
    val database = goalWithChildWrittenAt(identity, childUpdatedAt = "2026-08-06T11:45:00Z")
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals(Instant.parse("2026-08-06T11:45:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.FRESH, result.snapshot.freshness)
  }

  @Test
  fun `child workflow timestamps written by SQLite CURRENT_TIMESTAMP are parsed as UTC`() {
    val fixture = gitRepoFixture("ide-status-sqlite-timestamp")
    val identity = goalRepositoryIdentity(fixture)
    val database = goalWithChildWrittenAt(identity, childUpdatedAt = "2026-08-06 11:45:00")
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(Instant.parse("2026-08-06T11:45:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.FRESH, result.snapshot.freshness)
  }

  @Test
  fun `feature-goal with no child writes stays anchored to its own durable state`() {
    val fixture = gitRepoFixture("ide-status-goal-no-children")
    val database = TrackingDatabase(
      work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
      workflows = IdeStatusWorkflowStates(),
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(Instant.parse("2026-08-06T10:00:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.STALE, result.snapshot.freshness)
  }

  private fun goalWithChildWrittenAt(identity: String, childUpdatedAt: String): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-child", childUpdatedAt))
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
    return TrackingDatabase(
      work = listOf(
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
        workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"),
      ),
      workflows = workflows,
      controls = controls,
    )
  }

  private fun goalWithLaunchedChildDatabase(
    identity: String,
    childStarted: Instant,
    childArtifactsJson: String = "{}",
  ): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-child", "2026-08-06T11:00:00Z")
        .copy(startedAt = childStarted.toString(), artifactsJson = childArtifactsJson),
    )
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
    return TrackingDatabase(
      work = listOf(
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
        workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z")
          .copy(startedAt = childStarted),
      ),
      workflows = workflows,
      controls = controls,
    )
  }

  @Test
  fun `goal mid-planning projects the planning step label and a planning-progress summary`() {
    val fixture = gitRepoFixture("ide-status-goal-planning")
    val identity = goalRepositoryIdentity(fixture)
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child"),
        planning = planningSnapshot(GoalPlanningStatusState.PARTIALLY_PLANNED),
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    // The manifest carries lastResumableStep=implement, so this also proves the planning
    // override is ordered ahead of the projection currentStep preference.
    assertEquals("planning", result.snapshot.currentStep.id)
    assertEquals("Planning", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is planning subtasks (1/2 planned).", result.snapshot.summary)
    assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, result.snapshot.planning?.state)
    assertEquals("2", result.snapshot.planning?.currentPlanningSubtaskId)
  }

  @Test
  fun `running goal whose parent lease expired projects paused anchored at the last heartbeat`() {
    // A stopped or killed goal runner leaves current_state 'running' with no terminal write.
    // Reporting active would tick an elapsed clock for work that is not happening.
    val fixture = gitRepoFixture("ide-status-goal-lease-expired")
    val identity = goalRepositoryIdentity(fixture)
    val heartbeatAt = Instant.parse("2026-08-06T11:50:00Z")
    val lease = expiredLease(heartbeatAt)
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity, executionLease = lease)
    }
    val service = service(
      TrackingDatabase(
        work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
        workflows = IdeStatusWorkflowStates(),
        controls = controls,
      ),
      // Blank child workflow id keeps liveness on the parent-lease path. Mid-planning is the
      // real-world shape: a goal stopped before it ever launched a subtask.
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = ""),
        planning = planningSnapshot(GoalPlanningStatusState.PREPLANNED),
        lease = lease,
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusLifecycleState.PAUSED, result.snapshot.lifecycleState)
    // The elapsed clock settles at the stop time, not at the goal's state_entered_at.
    assertEquals(heartbeatAt, result.snapshot.updatedAt)
    // Inferred, not recorded: no durable pause row, so no paused_at may be synthesized —
    // updated_at alone carries the inferred stop anchor.
    val wire = result.snapshot.toStatusWireMap()
    assertFalse(wire.containsKey("paused_at"))
    assertEquals(heartbeatAt.toString(), wire["updated_at"])
    // The detail line must not describe planning as in flight while the goal is paused,
    // but the planning block itself is still reported.
    assertEquals("Goal SKILL-148 is paused.", result.snapshot.summary)
    assertEquals("planning", result.snapshot.currentStep.id)
  }

  private fun expiredLease(heartbeatAt: Instant): GoalRunnerExecutionLease = GoalRunnerExecutionLease(
    generation = 1,
    ownerToken = "owner-token",
    hostIdentity = "test-host",
    bootIdentity = "boot-id",
    pid = 4321,
    processBirthToken = "birth-token",
    heartbeatAt = heartbeatAt.toString(),
    expiresAt = heartbeatAt.plusSeconds(30).toString(),
  )

  @Test
  fun `goal with prepared planning keeps todays step and summary`() {
    val fixture = gitRepoFixture("ide-status-goal-planning-prepared")
    val identity = goalRepositoryIdentity(fixture)
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child"),
        planning = planningSnapshot(GoalPlanningStatusState.PREPARED),
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals("implement", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is active on implement.", result.snapshot.summary)
    assertEquals(GoalPlanningStatusState.PREPARED, result.snapshot.planning?.state)
  }

  @Test
  fun `goal with a null planning projection keeps todays step and summary and emits no planning`() {
    val fixture = gitRepoFixture("ide-status-goal-planning-absent")
    val identity = goalRepositoryIdentity(fixture)
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child"),
        planning = null,
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals("implement", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is active on implement.", result.snapshot.summary)
    assertNull(result.snapshot.planning)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("planning"))
  }

  @Test
  fun `non-goal families never carry planning`() {
    val fixture = gitRepoFixture("ide-status-planning-non-goal")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertNull(result.snapshot.planning)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("planning"))
  }

  @Test
  fun `blocked goal candidate stays blocked under paused and pause_requested controls`() {
    listOf(
      GoalRunnerControlState(paused = true, pauseReason = "operator_request", pausedAt = "2026-08-02T10:00:00Z"),
      GoalRunnerControlState(pauseRequested = true),
    ).forEachIndexed { index, controlState ->
      val fixture = gitRepoFixture("ide-status-goal-blocked-pause-$index")
      val identity = goalRepositoryIdentity(fixture)
      val service = service(
        goalOnlyDatabase(goalState = "blocked"),
        manifestStore = StubGoalManifestStore(
          goalManifestState(fixture, identity, childWorkflowId = "w-child")
            .copy(controlState = controlState.copy(repositoryIdentity = identity)),
        ),
      )

      val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

      assertEquals(IdeStatusLifecycleState.BLOCKED, result.snapshot.lifecycleState)
      assertEquals("Goal SKILL-148 is blocked.", result.snapshot.summary)
    }
  }

  @Test
  fun `active goal candidate projects paused once the pause is consumed`() {
    val wire = goalWireMapUnderControls(
      "ide-status-goal-active-pause-consumed",
      GoalRunnerControlState(paused = true, pauseReason = "operator_request", pausedAt = "2026-08-02T10:00:00Z"),
    ) { result ->
      assertEquals(IdeStatusLifecycleState.PAUSED, result.snapshot.lifecycleState)
      assertEquals("Goal SKILL-148 is paused.", result.snapshot.summary)
    }

    assertEquals("paused", wire["lifecycle_state"])
    assertEquals("2026-08-02T10:00:00Z", wire["paused_at"])
    assertFalse(wire.containsKey("pause_requested"))
  }

  @Test
  fun `active goal candidate with an unconsumed pause request stays active and reports the request`() {
    // A requested-but-unconsumed pause is still genuinely running its current subtask;
    // collapsing it into paused would reintroduce the "says stopped while working" lie.
    val wire = goalWireMapUnderControls(
      "ide-status-goal-active-pause-requested",
      GoalRunnerControlState(pauseRequested = true),
    ) { result ->
      assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    }

    assertEquals("active", wire["lifecycle_state"])
    assertEquals(true, wire["pause_requested"])
    assertFalse(wire.containsKey("paused_at"))
  }

  @Test
  fun `plain active goal emits neither pause signal`() {
    val wire = goalWireMapUnderControls(
      "ide-status-goal-active-no-pause",
      GoalRunnerControlState(),
    ) { result ->
      assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    }

    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }

  /**
   * Zero is not an observation that the goal did no work. Emitting it would have the widget render
   * "Goal elapsed: 0s" as fact for every goal predating the accumulator, instead of letting the
   * consumer fall back to its own clock.
   */
  @Test
  fun `a goal with no recorded execution omits the active duration rather than publishing zero`() {
    val wire = goalWireMapUnderControls(
      "ide-status-goal-active-duration-unrecorded",
      GoalRunnerControlState(),
    ) { result ->
      assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    }

    assertFalse(wire.containsKey("active_duration_ms"))
    assertFalse(wire.containsKey("active_duration_as_of"))
  }

  /**
   * A killed runner never reaches releaseExecutionLease, so its anchor survives in the durable
   * record. Publishing it would license the consumer to add an unbounded `now - anchor` tail and
   * rebuild the inflated clock the accumulator exists to remove.
   */
  @Test
  fun `a stale anchor from a dead runner is withheld while the accumulated total still ships`() {
    val wire = goalWireMapUnderControls(
      "ide-status-goal-active-duration-stale-anchor",
      GoalRunnerControlState(
        activeDurationMs = 90_000,
        activeDurationAsOf = "2026-08-06T09:00:00Z",
      ),
    ) { result ->
      assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    }

    assertEquals(90_000L, wire["active_duration_ms"])
    assertFalse(wire.containsKey("active_duration_as_of"))
  }

  @Test
  fun `non-goal families never carry pause signals`() {
    val fixture = gitRepoFixture("ide-status-pause-non-goal")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    val wire = result.snapshot.toStatusWireMap()
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }

  private fun goalWireMapUnderControls(
    fixtureName: String,
    controlState: GoalRunnerControlState,
    assertSnapshot: (IdeStatusResult) -> Unit,
  ): Map<String, Any?> {
    val fixture = gitRepoFixture(fixtureName)
    val identity = goalRepositoryIdentity(fixture)
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child")
          .copy(controlState = controlState.copy(repositoryIdentity = identity)),
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertSnapshot(result)
    return result.snapshot.toStatusWireMap()
  }

  private fun goalOnlyDatabase(goalState: String = "running"): TrackingDatabase = TrackingDatabase(
    work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, goalState, "2026-08-06T10:00:00Z")),
    workflows = IdeStatusWorkflowStates(),
  )

  private fun planningSnapshot(state: GoalPlanningStatusState): GoalPlanningStatusSnapshot = GoalPlanningStatusSnapshot(
    state = state,
    sharedPreplanPrepared = true,
    plannedSubtaskCount = 1,
    totalSubtaskCount = 2,
    currentPlanningSubtaskId = 2,
    reason = null,
  )

  private fun goalManifestState(fixture: Path, identity: String, childWorkflowId: String): GoalRunnerManifestState =
    GoalRunnerManifestState(
      parentWorkflowId = "goal-1",
      dbPath = "/fake/ide-status.db",
      manifest = DecompositionManifest(
        issueKey = "SKILL-148",
        featureName = "ide-status",
        parentSpecPath = ".feature-specs/SKILL-148/spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-148",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
        subtasks = listOf(
          DecompositionSubtask(
            id = 1,
            name = "One",
            specPath = "spec_1.md",
            status = "complete",
            workflowId = "w-done",
          ),
          DecompositionSubtask(
            id = 2,
            name = "Two",
            specPath = "spec_2.md",
            status = "in_progress",
            workflowId = childWorkflowId,
            lastResumableStep = "implement",
          ),
        ),
      ),
      controlState = GoalRunnerControlState(repositoryIdentity = identity),
      repoRoot = fixture,
    )

  private fun service(
    database: TrackingDatabase,
    manifestStore: GoalRunnerManifestStore = EmptyManifestStore,
  ): IdeStatusService {
    val snapshotValidator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
    }
    val phaseRecorder = FeatureTaskRuntimePhaseRecorder(
      database,
      snapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
    val projector = IdeStatusProjector(
      workflowSnapshotValidator = snapshotValidator,
      goalRunnerStatusService = GoalRunnerStatusService(
        manifestStore = manifestStore,
        outcomeStore = EmptyOutcomeStore,
        phaseRecorder = phaseRecorder,
      ),
      featureTaskRuntimeStatusService = FeatureTaskRuntimeStatusService(
        recorder = phaseRecorder,
        runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, snapshotValidator),
        decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, snapshotValidator),
      ),
    )
    return IdeStatusService(
      database = database,
      projector = projector,
      ideStatusValidator = EmitShapeValidator,
      branchSource = CheckedOutBranchSource(::fixtureCheckedOutBranch),
      clock = clock,
    )
  }
}

private fun fixtureCheckedOutBranch(repoRoot: Path): String? =
  runCatching { Files.readString(repoRoot.resolve(".git").resolve("HEAD")) }.getOrNull()
    ?.trim()
    ?.takeIf { it.startsWith("ref: refs/heads/") }
    ?.removePrefix("ref: refs/heads/")

private object EmitShapeValidator : IdeStatusValidator by NoopIdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
    require(snapshot["contract_version"] == "0.1")
    require(snapshot["repository_identity"] is String)
    require(snapshot["lifecycle_state"] is String)
  }
}

/**
 * Selection is scoped to the checked-out branch, so the fixture checks out a branch
 * referencing the harness issue key unless a test overrides it. `branch = null` writes
 * no HEAD, modelling a detached/unresolvable checkout that disables scoping.
 */
private fun gitRepoFixture(prefix: String, branch: String? = "feat/SKILL-148-fixture"): Path {
  val root = Files.createTempDirectory(prefix)
  Files.createDirectory(root.resolve(".git"))
  if (branch != null) {
    Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/$branch\n")
  }
  return root.toRealPath()
}

private fun workItem(workflowId: String, kind: WorkItemKind, state: String, updatedAt: String): WorkItem = WorkItem(
  issueKey = "SKILL-148",
  workflowKind = kind,
  workflowId = workflowId,
  startedAt = Instant.parse("2026-08-06T08:00:00Z"),
  currentState = state,
  stateEnteredAt = Instant.parse(updatedAt),
  stateEnteredAtEstimated = false,
)

private fun identityFor(workflowId: String, repositoryIdentity: String): FeatureTaskExecutionIdentity =
  FeatureTaskExecutionIdentity(
    workflowId = workflowId,
    normalizedIssueKey = "SKILL-148",
    repositoryIdentity = repositoryIdentity,
    governedSpecPath = "spec.md",
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )

private data class PhaseRecordOptions(
  val effort: String? = null,
  val attemptCount: Int = 1,
  val reviewPassNumber: Int? = null,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
)

private fun phaseRecordWire(
  phaseId: String,
  status: String,
  launchedModel: String?,
  options: PhaseRecordOptions = PhaseRecordOptions(),
): Map<String, Any?> = FeatureTaskRuntimePhaseRecord(
  phaseId = phaseId,
  status = status,
  attemptCount = options.attemptCount,
  startedAt = "2026-08-06T09:00:00Z",
  finishedAt = if (status == "completed") "2026-08-06T09:30:00Z" else null,
  resolvedAgentId = "claude",
  launchedModel = launchedModel,
  launchedEffort = options.effort,
  reviewPassNumber = options.reviewPassNumber,
  loopId = options.loopId,
  edgeIteration = options.edgeIteration,
).toArtifactMap()

private fun phaseRecordsArtifactsJson(vararg records: Pair<String, Map<String, Any?>>): String =
  JsonSupport.mapToJsonString(
    mapOf(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to records.toMap()),
  )

private fun runtimeRecord(
  workflowId: String,
  updatedAt: String,
  currentStep: String = "implement",
): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = "session-$workflowId",
  workflowName = "bill-feature-task",
  contractVersion = "0.1",
  workflowStatus = if (currentStep == "pr") "completed" else "running",
  currentStepId = currentStep,
  stepsJson = pipelineStepsJson(FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds, currentStep),
  artifactsJson = "{}",
  startedAt = "2026-08-06T08:00:00Z",
  updatedAt = updatedAt,
  finishedAt = if (currentStep == "pr") updatedAt else null,
  issueKey = "SKILL-148",
  mode = FeatureTaskWorkflowMode.RUNTIME,
)

private fun verifyRecord(
  workflowId: String,
  updatedAt: String,
  currentStep: String = "code_review",
): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = "session-$workflowId",
  workflowName = "bill-feature-verify",
  contractVersion = "0.1",
  workflowStatus = "running",
  currentStepId = currentStep,
  stepsJson = pipelineStepsJson(FeatureVerifyWorkflowDefinition.definition.stepIds, currentStep),
  artifactsJson = "{}",
  startedAt = "2026-08-06T08:00:00Z",
  updatedAt = updatedAt,
  finishedAt = null,
  issueKey = "SKILL-148",
)

private fun pipelineStepsJson(stepIds: List<String>, currentStep: String): String {
  val currentIndex = stepIds.indexOf(currentStep).takeIf { it >= 0 }
    ?: error("Unknown step id '$currentStep' for fixture pipeline.")
  return stepIds.mapIndexed { index, stepId ->
    val status = when {
      index < currentIndex -> "completed"
      index == currentIndex && currentStep == "pr" -> "completed"
      index == currentIndex -> "running"
      else -> "pending"
    }
    """{"step_id":"$stepId","status":"$status","attempt_count":1}"""
  }.joinToString(prefix = "[", postfix = "]")
}

private class TrackingDatabase(
  private val work: List<WorkItem>,
  private val workflows: WorkflowStateRepository,
  private val exists: Boolean = true,
  private val controls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository,
) : DatabaseSessionFactory {
  var readCalls: Int = 0
    private set
  var writeCalls: Int = 0
    private set

  override fun resolveDbPath(dbOverride: String?): Path = Path.of("/fake/ide-status.db")

  override fun databaseExists(dbOverride: String?): Boolean = exists

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T {
    readCalls += 1
    return block(unitOfWork())
  }

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = Path.of("/fake/ide-status.db")
    override val workflowStates = workflows
    override val workList: WorkListRepository = object : WorkListRepository {
      override fun list(limit: Int?): List<WorkItem> = limit?.let(work::take) ?: work
    }
    override val goalRunnerControls = controls
    override val learnings: LearningRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val reviews: ReviewRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
    override val featureTaskRuntimeAuditGenerations = EmptyFeatureTaskRuntimeAuditGenerationRepository
  }
}

/**
 * Models the orphaned-row seam: `WorkflowStateStore` raises a typed [InvalidWorkflowStateSchemaError]
 * when a feature-task identity has no workflow row, so the service must report it rather than let an
 * uncaught failure escape.
 */
private class OrphanedIdentityWorkflowStates(
  private val message: String,
) : WorkflowStateRepository by IdeStatusWorkflowStates() {
  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    throw InvalidWorkflowStateSchemaError(message)
}

private class IdeStatusWorkflowStates : WorkflowStateRepository {
  private val implement = mutableMapOf<String, WorkflowStateRecord>()
  private val verify = mutableMapOf<String, WorkflowStateRecord>()
  private val identities = mutableMapOf<String, FeatureTaskExecutionIdentity>()

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    identities[identity.workflowId] = identity
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    identities[workflowId]

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = identities.values
    .filter {
      it.routeScope == FeatureTaskRouteScope.GOAL_CHILD &&
        it.normalizedIssueKey == normalizedIssueKey &&
        it.repositoryIdentity == repositoryIdentity
    }
    .mapNotNull { identity ->
      implement[identity.workflowId]?.let { FeatureTaskWorkflowCandidate(identity = identity, workflow = it) }
    }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int = identities.values.count {
    it.normalizedIssueKey == normalizedIssueKey && it.routeScope == FeatureTaskRouteScope.GOAL_CHILD
  }

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implement[row.workflowId] = row
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    implement.values.toList().take(limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = implement.values.lastOrNull()

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    verify[row.workflowId] = row
  }

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = verify[workflowId]

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = verify.values.toList().take(limit)

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = verify.values.lastOrNull()

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  // The runtime projector reads rows via getFeatureTaskWorkflowAsMode(mode=RUNTIME) ->
  // getFeatureTaskRuntimeWorkflow; back it with the same shared feature-task row map the
  // implement alias writes, mirroring the production shared feature-task workflow store.
  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    implement[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    implement.values.toList().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? = implement.values.lastOrNull()
}

private class StubGoalManifestStore(
  private val state: GoalRunnerManifestState,
  private val planning: GoalPlanningStatusSnapshot? = null,
  private val lease: GoalRunnerExecutionLease? = null,
) : GoalRunnerManifestStore {
  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? = lease

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    state.takeIf { it.manifest.issueKey.equals(issueKey, ignoreCase = true) }

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ): GoalPlanningStatusSnapshot? = planning

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = false
}

private object EmptyManifestStore : GoalRunnerManifestStore {
  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = false
}

private object EmptyOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: Map<String, Any?>,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String? = null

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? = null

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean =
    false

  override fun recordSessionAccounting(
    request: GoalRunnerSessionAccountingRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = false

  override fun ledgerSequenceWatermarks(
    issueKey: String,
    dbPathOverride: String?,
  ): skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks =
    skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks()

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean = false
}
