package skillbill.application

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.toRecord
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowUpdateInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskContinuationLookupServiceTest {
  @Test
  fun `lookup returns no match without mutating workflow state`() {
    val fixture = fixture()

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    assertEquals(emptyList(), fixture.states.listFeatureTaskRuntimeWorkflows())
  }

  @Test
  fun `lookup isolates equal issue keys by canonical repository identity`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_B)

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("skill-120", REPOSITORY_A),
    )
    val running = assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_B),
    )
    assertEquals(opened.workflowId, running.candidate.workflowId)
  }

  @Test
  fun `lookup reports every ambiguous eligible workflow without selecting newest`() {
    val fixture = fixture()
    val first = fixture.open(REPOSITORY_A)
    val second = fixture.open(REPOSITORY_A)

    val ambiguous = assertIs<FeatureTaskContinuationLookupResult.Ambiguous>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )

    assertEquals(setOf(first.workflowId, second.workflowId), ambiguous.candidates.map { it.workflowId }.toSet())
  }

  @Test
  fun `lookup keeps terminal-only history terminal`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    fixture.service.abandonFeatureTaskRuntime(opened.workflowId, "Terminal lookup fixture.")

    val terminal = assertIs<FeatureTaskContinuationLookupResult.TerminalOnly>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )

    assertEquals(listOf(opened.workflowId), terminal.candidates.map { it.workflowId })
  }

  @Test
  fun `only one caller can claim a resumable workflow`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    fixture.service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        sessionId = "",
      ),
    )
    val candidate = assertIs<FeatureTaskContinuationLookupResult.Resumable>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    ).candidate

    assertTrue(fixture.lookup.claim(candidate))
    assertFalse(fixture.lookup.claim(candidate))
    val running = assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A, candidate.workflowId),
    )
    assertEquals(candidate.workflowId, running.candidate.workflowId)
    assertEquals("ownership_unavailable", running.candidate.liveness?.classification)
  }

  @Test
  fun `feature-task creation rejects malformed identity before persistence`() {
    val fixture = fixture()

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      fixture.service.openFeatureTask(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        issueKey = "SKILL-120",
        repositoryIdentity = "not-a-repository",
        governedSpecPath = ".feature-specs/SKILL-120-continuation/spec.md",
      )
    }

    assertEquals(emptyList(), fixture.states.listFeatureTaskRuntimeWorkflows())
  }

  @Test
  fun `identity-less matching feature-task loud-fails instead of becoming no match`() {
    val fixture = fixture()
    assertIs<WorkflowOpenResult.Ok>(
      fixture.service.open(WorkflowFamilyKind.TASK_RUNTIME, issueKey = "SKILL-120"),
    )

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A)
    }
  }

  @Test
  fun `malformed identity from another repository cannot poison scoped lookup`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_B)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(contractVersion = "corrupt"))

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
  }

  @Test
  fun `conflicting identity in the selected repository loud-fails`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(normalizedIssueKey = "SKILL-999"))

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A)
    }
  }

  @Test
  fun `legacy prose-mode candidate loud-fails on continuation with the runtime re-run error`() {
    // SKILL-175 subtask 6 AC-002: a candidate whose immutable identity decodes to PROSE is
    // quarantined in FeatureTaskContinuationLookupService.project, raising LegacyProseWorkflowError
    // (which names the `skill-bill goal <KEY>` re-run path) rather than being handed back as resumable.
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(mode = FeatureTaskWorkflowMode.PROSE))
    // The durable workflow row must decode as PROSE too: the lookup validates identity-vs-snapshot
    // consistency before the mode quarantine, so a RUNTIME row would trip the schema error instead.
    val row = requireNotNull(fixture.states.getFeatureTaskWorkflow(opened.workflowId))
    fixture.states.saveFeatureTaskRuntimeWorkflow(row.copy(mode = FeatureTaskWorkflowMode.PROSE))

    assertFailsWith<LegacyProseWorkflowError> {
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A)
    }
  }

  @Test
  fun `goal-child identity stays outside standalone continuation lookup`() {
    val fixture = fixture()
    val opened = assertIs<WorkflowOpenResult.Ok>(
      fixture.service.openFeatureTask(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        issueKey = "SKILL-120",
        repositoryIdentity = REPOSITORY_A,
        governedSpecPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      ),
    )

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    fixture.service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "preplan",
        sessionId = "",
      ),
    )

    val resumable = assertIs<FeatureTaskContinuationLookupResult.Resumable>(
      fixture.lookup.lookupGoalChild("SKILL-120", REPOSITORY_A, opened.workflowId),
    )
    assertEquals(opened.workflowId, resumable.candidate.workflowId)
  }

  @Test
  fun `lookup surfaces a prepared goal that owns durable state instead of reporting no match`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = "paused", manifestStatus = "in_progress")

    val goal = assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )

    assertEquals("wfl-goal-parent", goal.candidate.parentWorkflowId)
    assertEquals("paused", goal.candidate.status)
    assertEquals(1, goal.candidate.currentSubtaskId)
    assertEquals(1, goal.candidate.pendingCount)
  }

  @Test
  fun `lookup reports a running goal so a second run is never started against the same state`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = "running", manifestStatus = "in_progress")

    val goal = assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )

    assertEquals("running", goal.candidate.status)
  }

  @Test
  fun `lookup keeps a completed goal out of continuation`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = "paused", manifestStatus = "complete")

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
  }

  @Test
  fun `lookup isolates a goal bound to another repository by its children`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = "paused", manifestStatus = "in_progress")
    assertIs<WorkflowOpenResult.Ok>(
      fixture.service.openFeatureTask(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        issueKey = "SKILL-120",
        repositoryIdentity = REPOSITORY_B,
        governedSpecPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      ),
    )

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_B),
    )
  }

  private fun fixture(): Fixture {
    val states = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(states)
    val service = WorkflowService(
      database = database,
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )
    return Fixture(
      states = states,
      service = service,
      lookup = FeatureTaskContinuationLookupService(
        database,
        testWorkflowSnapshotValidator,
        testDecompositionManifestValidator,
      ),
    )
  }

  private data class Fixture(
    val states: InMemoryWorkflowStates,
    val service: WorkflowService,
    val lookup: FeatureTaskContinuationLookupService,
  ) {
    fun saveGoalParent(workflowStatus: String, manifestStatus: String) {
      val manifest = DecompositionManifest(
        issueKey = "SKILL-120",
        featureName = "goal-continuation",
        parentSpecPath = ".feature-specs/SKILL-120-goal/spec.md",
        status = manifestStatus,
        baseBranch = "main",
        featureBranch = "feat/SKILL-120-goal",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
        subtasks = listOf(
          DecompositionSubtask(
            id = 1,
            name = "first",
            specPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
            status = if (manifestStatus == "complete") "complete" else "pending",
          ),
        ),
      )
      val definition = skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.definition
      val engine = WorkflowEngine(testWorkflowSnapshotValidator)
      val opened = engine.openRecord(definition, "wfl-goal-parent", "ftr-goal", "preplan")
      states.saveFeatureTaskRuntimeWorkflow(
        engine.updateRecord(
          definition,
          opened,
          WorkflowUpdateInput(
            workflowStatus = workflowStatus,
            currentStepId = "plan",
            stepUpdates = null,
            artifactsPatch = mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                encodeDecompositionManifestMap(manifest, testDecompositionManifestValidator),
            ),
            sessionId = "ftr-goal",
          ),
        ).toRecord().copy(issueKey = "SKILL-120"),
      )
    }

    fun open(repositoryIdentity: String): WorkflowOpenResult.Ok = assertIs(
      service.openFeatureTask(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        issueKey = "SKILL-120",
        repositoryIdentity = repositoryIdentity,
        governedSpecPath = ".feature-specs/SKILL-120-continuation/spec.md",
      ),
    )
  }

  private companion object {
    const val REPOSITORY_A = "repo-root-realpath-v1:/tmp/skill-bill-a"
    const val REPOSITORY_B = "repo-root-realpath-v1:/tmp/skill-bill-b"
  }
}
