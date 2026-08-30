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

class IdeStatusServiceGoalProjectionTest {

  @Test
  fun `running goal whose parent lease expired projects paused anchored at the last heartbeat`() {
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
                  manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = ""),
        planning = planningSnapshot(GoalPlanningStatusState.PREPLANNED),
        lease = lease,
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.PAUSED, result.snapshot.lifecycleState)
        assertEquals(heartbeatAt, result.snapshot.updatedAt)
            val wire = result.snapshot.toStatusWireMap()
    assertFalse(wire.containsKey("paused_at"))
    assertEquals(heartbeatAt.toString(), wire["updated_at"])
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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

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

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

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

      val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

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
  fun `live goal snapshot includes current subtask active duration when recorded`() {
    val fixture = gitRepoFixture("ide-status-subtask-active-duration-live")
    val identity = goalRepositoryIdentity(fixture)
    val asOf = Instant.parse("2026-08-06T11:59:00Z")
    val lease = GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "owner-token",
      hostIdentity = "test-host",
      bootIdentity = "boot-id",
      pid = 4321,
      processBirthToken = "birth-token",
      heartbeatAt = asOf.toString(),
      expiresAt = ideStatusObservedAt.plusSeconds(30).toString(),
    )
    val service = service(
      goalOnlyDatabase(),
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "")
          .copy(
            controlState = GoalRunnerControlState(
              repositoryIdentity = identity,
              executionLease = lease,
              activeDurationMs = 120_000,
              activeDurationAsOf = asOf.toString(),
              currentSubtaskId = 2,
              subtaskActiveDurationMs = 45_000,
              subtaskActiveDurationAsOf = asOf.toString(),
            ),
          ),
        lease = lease,
      ),
    )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals(45_000L, result.snapshot.currentSubtask?.activeDurationMs)
    assertEquals(asOf, result.snapshot.currentSubtask?.activeDurationAsOf)
    val nested = nestedWireMap(result.snapshot.toStatusWireMap(), "current_subtask")
    assertEquals(45_000L, nested["active_duration_ms"])
    assertEquals(asOf.toString(), nested["active_duration_as_of"])
  }

  @Test
  fun `goal with no recorded subtask execution omits nested active duration`() {
    val wire = goalWireMapUnderControls(
      "ide-status-subtask-active-duration-unrecorded",
      GoalRunnerControlState(currentSubtaskId = 2),
    ) { result ->
      assertEquals("2", result.snapshot.currentSubtask?.id)
    }

    val nested = nestedWireMap(wire, "current_subtask")
    assertFalse(nested.containsKey("active_duration_ms"))
    assertFalse(nested.containsKey("active_duration_as_of"))
  }

  @Test
  fun `a stale subtask anchor from a dead runner is withheld while the accumulated total still ships`() {
    val wire = goalWireMapUnderControls(
      "ide-status-subtask-active-duration-stale-anchor",
      GoalRunnerControlState(
        currentSubtaskId = 2,
        subtaskActiveDurationMs = 50_000,
        subtaskActiveDurationAsOf = "2026-08-06T09:00:00Z",
      ),
    ) { result ->
      assertEquals("2", result.snapshot.currentSubtask?.id)
      assertEquals(50_000L, result.snapshot.currentSubtask?.activeDurationMs)
      assertNull(result.snapshot.currentSubtask?.activeDurationAsOf)
    }

    val nested = nestedWireMap(wire, "current_subtask")
    assertEquals(50_000L, nested["active_duration_ms"])
    assertFalse(nested.containsKey("active_duration_as_of"))
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

    val result = service(database).status(IdeStatusRequest(repoRoot = fixture.toString(), ideStatusObservedAt = ideStatusObservedAt))

    val wire = result.snapshot.toStatusWireMap()
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }
}
