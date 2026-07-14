package skillbill.application

import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.workflow.WorkflowService
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FeatureTaskRouterContinuationTest {
  @Test
  fun `prose router continuation after plan preserves identity and supplies only completed plan`() {
    val states = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(states)
    val service = WorkflowService(
      database = database,
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )
    val lookup = FeatureTaskContinuationLookupService(database, testWorkflowSnapshotValidator)
    val opened = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        kind = WorkflowFamilyKind.TASK_PROSE,
        issueKey = "SKILL-120",
        repositoryIdentity = REPOSITORY_IDENTITY,
        governedSpecPath = SPEC_PATH,
        sessionId = SESSION_ID,
      ),
    )
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "preplan_digest" to mapOf("risk" to "bounded"),
          "plan" to mapOf("tasks" to listOf("add continuation integration coverage")),
        ),
      ),
    )

    val candidate = assertIs<FeatureTaskContinuationLookupResult.Resumable>(
      lookup.lookup("skill-120", REPOSITORY_IDENTITY),
    ).candidate
    val continued = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, candidate.workflowId),
    ).view

    assertEquals(opened.workflowId, candidate.workflowId)
    assertEquals(opened.workflowId, continued.compact.workflowId)
    assertEquals(SESSION_ID, continued.resume.snapshot.sessionId)
    assertEquals("implement", continued.continueStepId)
    assertEquals(listOf("plan"), continued.compact.requiredArtifactKeys)
    assertEquals(listOf("plan"), continued.compact.currentStepArtifacts.map { it.key })
    assertFalse(continued.stepArtifacts.containsKey("preplan_digest"))
    val repeatedLookup = assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
      lookup.lookup("SKILL-120", REPOSITORY_IDENTITY),
    )
    assertEquals(opened.workflowId, repeatedLookup.candidate.workflowId)
  }

  private companion object {
    const val SESSION_ID = "session-skill-120"
    const val REPOSITORY_IDENTITY = "repo-root-realpath-v1:/tmp/skill-bill"
    const val SPEC_PATH = ".feature-specs/SKILL-120-db-first-feature-continuation/spec.md"
  }
}
