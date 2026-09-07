package skillbill.application.work

import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.application.idestatus.model.IdeStatusFreshness
import skillbill.application.idestatus.model.IdeStatusLifecycleState
import skillbill.application.idestatus.model.IdeStatusProblemCode
import skillbill.application.idestatus.model.IdeStatusRequest
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class IdeStatusServiceBranchScopingTest {

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
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

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
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    val execution = requireNotNull(result.snapshot.currentPhaseExecution)
    assertEquals("review", result.snapshot.currentStep.id)
    assertEquals("review", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(2, execution.count)
  }

  @Test
  fun `goal prefers child derived phase over stale child current_step_id`() {
    val fixture = gitRepoFixture("ide-status-goal-stale-step")
    val identity = goalRepositoryIdentity(fixture)
    val database = goalWithLaunchedChildDatabase(
      identity,
      Instant.parse("2026-08-06T09:15:00Z"),
      childCurrentStep = "verify_findings",
      childArtifactsJson = phaseRecordsArtifactsJson(
        "preplan" to phaseRecordWire("preplan", "completed", null),
        "plan" to phaseRecordWire("plan", "completed", null),
        "implement" to phaseRecordWire("implement", "completed", null),
        "audit" to phaseRecordWire("audit", "completed", null),
        "review" to phaseRecordWire("review", "completed", null),
        "verify_findings" to phaseRecordWire("verify_findings", "completed", null),
      ),
    )
    val staleProgress = GoalRunnerWorkflowProgress(
      workflowId = "w-child",
      workflowStatus = "running",
      currentStepId = "verify_findings",
      progressToken = "stale-verify-findings",
      latestLivenessSignal = "workflow_status=running; step=verify_findings",
    )
    val result = service(
      database,
      manifestStore = StubGoalManifestStore(
        goalManifestState(fixture, identity, childWorkflowId = "w-child").let { state ->
          state.copy(
            manifest = state.manifest.copy(
              subtasks = state.manifest.subtasks.map { subtask ->
                if (subtask.id == 2) subtask.copy(lastResumableStep = "verify_findings") else subtask
              },
            ),
          )
        },
      ),
      outcomeStore = object : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
        override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
          staleProgress.takeIf { workflowId == "w-child" }
      },
    ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals("validate", result.snapshot.currentStep.id)
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

    val first = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )
    val second = service.status(
      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt.plusSeconds(60)),
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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service(database).status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals("No recent Skill Bill work for branch 'feat/OTHER-9-unrelated'.", result.snapshot.summary)
  }

  @Test
  fun `branch scoping requires a whole issue-key token, not a prefix hit`() {
    val fixture = gitRepoFixture("ide-status-branch-token", branch = "feat/SKILL-14-prefix")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service(database).status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `protected base branch disables scoping so pre-branch work stays visible`() {
    val fixture = gitRepoFixture("ide-status-branch-protected", branch = "main")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )

    val result = service(database).status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

    assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState)
    assertEquals("done", result.snapshot.currentStep.id)
    assertEquals("Complete", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is complete.", result.snapshot.summary)
  }

  @Test
  fun `blocked or failed goal row with every subtask settled projects terminal complete`() {
    listOf("blocked", "failed").forEach { stuckState ->
      val fixture = gitRepoFixture("ide-status-goal-settled-$stuckState")
      val identity = goalRepositoryIdentity(fixture)
      val service = service(
        goalOnlyDatabase(goalState = stuckState),
        manifestStore = StubGoalManifestStore(
          completedGoalManifestState(fixture, identity),
        ),
      )

      val result = service.status(

        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

      )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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
    val database = goalWithChildWrittenAt(identity, childUpdatedAt = "2026-08-06T11:45:00Z")
    val service = service(database)

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

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
    childCurrentStep: String = "implement",
  ): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      runtimeRecord("w-child", "2026-08-06T11:00:00Z", currentStep = childCurrentStep)
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

    val result = service.status(

      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),

    )

    assertEquals("planning", result.snapshot.currentStep.id)
    assertEquals("Planning", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is planning subtasks (1/2 planned).", result.snapshot.summary)
    assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, result.snapshot.planning?.state)
    assertEquals("2", result.snapshot.planning?.currentPlanningSubtaskId)
  }
}
