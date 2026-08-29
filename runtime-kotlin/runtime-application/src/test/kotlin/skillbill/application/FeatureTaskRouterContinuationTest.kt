package skillbill.application

import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.openFeatureTask
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FeatureTaskRouterContinuationTest {
  @Test
  fun `runtime router continuation after plan preserves identity and supplies only completed plan`() {
    // Resume presence SoT is completed private phase records
    // (FeatureTaskRuntimeRequiredArtifactPresenceResolver), not top-level preplan_digest/plan maps.
    // compactContinueView falls back to requiredKeys when no declared projection synthesizes
    // repository_evidence; WorkflowEngine.resumeView also filters RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY
    // from missingArtifacts — so currentStepArtifacts is [plan], matching WorkflowCompactContinuationTest.
    val states = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(states)
    val service = WorkflowService(
      database = database,
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )
    val lookup = FeatureTaskContinuationLookupService(
      database,
      testWorkflowSnapshotValidator,
      testDecompositionManifestValidator,
    )
    val opened = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-120",
          repositoryIdentity = REPOSITORY_IDENTITY,
          governedSpecPath = SPEC_PATH,
          sessionId = SESSION_ID,
        ),
      ),
    )
    service.update(WorkflowFamilyKind.TASK_RUNTIME, blockedAtImplementAfterPlan(opened.workflowId))

    val candidate = assertIs<FeatureTaskContinuationLookupResult.Resumable>(
      lookup.lookup("skill-120", REPOSITORY_IDENTITY),
    ).candidate
    val continued = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, candidate.workflowId),
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

  private fun blockedAtImplementAfterPlan(workflowId: String): WorkflowUpdateRequest = WorkflowUpdateRequest(
    workflowId = workflowId,
    workflowStatus = "blocked",
    currentStepId = "implement",
    stepUpdates = listOf(
      mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
      mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
      mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
    ),
    artifactsPatch = mapOf(
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "preplan" to completedPhaseRecord("preplan"),
        "plan" to completedPhaseRecord(
          "plan",
          outputArtifact = """{"tasks":["add continuation integration coverage"]}""",
        ),
      ),
    ),
  )

  private fun completedPhaseRecord(phaseId: String, outputArtifact: String? = null): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "private_phase_record",
    "phase_id" to phaseId,
    "status" to "completed",
    "attempt_count" to 1,
    "started_at" to "2026-08-09T10:00:00Z",
    "first_started_at" to "2026-08-09T10:00:00Z",
    "finished_at" to "2026-08-09T10:01:00Z",
    "resolved_agent_id" to "agent-$phaseId",
    "execution_origin" to "agent-executed",
  ).apply {
    outputArtifact?.let { put("output_artifact", it) }
  }

  private companion object {
    const val SESSION_ID = "session-skill-120"
    const val REPOSITORY_IDENTITY = "repo-root-realpath-v1:/tmp/skill-bill"
    const val SPEC_PATH = ".feature-specs/SKILL-120-db-first-feature-continuation/spec.md"
  }
}
