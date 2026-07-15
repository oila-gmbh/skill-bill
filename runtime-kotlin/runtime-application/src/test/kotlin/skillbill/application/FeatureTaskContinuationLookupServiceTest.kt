package skillbill.application

import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.workflow.WorkflowService
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
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
      lookup = FeatureTaskContinuationLookupService(database, testWorkflowSnapshotValidator),
    )
  }

  private data class Fixture(
    val states: InMemoryWorkflowStates,
    val service: WorkflowService,
    val lookup: FeatureTaskContinuationLookupService,
  ) {
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
