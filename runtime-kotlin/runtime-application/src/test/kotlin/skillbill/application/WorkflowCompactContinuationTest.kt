package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.workflow.WorkflowService
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.WorkflowEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The closed-world launch declaration bounds the lossless projection before
 * either the compact or full adapter payload is produced.
 */
private const val WORKFLOW_INPUT_PROJECTION_BYTE_CEILING = 64 * 1024

class WorkflowCompactContinuationTest {
  @Test
  fun `continueWorkflow compact projection inlines small current-step artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "branch" to mapOf("branch_name" to "feat/demo"),
          "plan" to mapOf("mode" to "implement", "task_count" to 1),
          "preplan_digest" to mapOf("risk" to "low"),
        ),
      ),
    )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, opened.workflowId),
    )
    val compact = standard.view.compact

    assertEquals("reopened", compact.continueStatus)
    assertEquals("reopened", standard.view.continueStatus)
    assertEquals("blocked", compact.workflowStatusBeforeContinue)
    assertEquals("blocked", standard.view.workflowStatusBeforeContinue)
    assertEquals(opened.workflowId, compact.workflowId)
    assertEquals("bill-feature-task", compact.skillName)
    assertEquals("implement", compact.resumeStepId)
    assertEquals("Step 4: Execute Plan", compact.resumeStepLabel)
    assertEquals(listOf("plan"), compact.requiredArtifactKeys)
    assertEquals(listOf("branch", "plan", "preplan_digest"), compact.availableArtifactKeys)
    assertEquals(listOf("branch", "preplan_digest"), compact.omittedArtifactKeys)
    assertTrue(compact.continuationBrief.contains(opened.workflowId))
    assertTrue(compact.continuationEntryPrompt.contains("Continue status: reopened"))
    assertTrue(compact.continuationBrief.contains("`current_step_artifacts`"))
    assertTrue(compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertTrue(compact.continuationEntryPrompt.contains("Omitted artifact keys: branch"))
    assertTrue(
      compact.continuationBrief.contains(
        "Omitted artifact keys (branch, preplan_digest) remain private phase context",
      ),
    )
    assertFalse(compact.continuationBrief.contains("`step_artifacts`"))
    assertFalse(compact.continuationEntryPrompt.contains("Recovered artifacts:"))
    val planSummary = compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertTrue(planSummary.inline)
    assertFalse(planSummary.truncated)
    assertEquals(mapOf("mode" to "implement", "task_count" to 1), planSummary.value)
  }

  @Test
  fun `continueWorkflow compact projection losslessly inlines large current-step artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(5000)),
          "preplan_digest" to mapOf("risk" to "low"),
        ),
      ),
    )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, opened.workflowId),
    )
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }

    assertTrue(planSummary.present)
    assertTrue(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertEquals(mapOf("mode" to "implement", "body" to "x".repeat(5000)), planSummary.value)
    assertEquals(null, planSummary.preview)
    assertFalse(planSummary.truncated)
    assertFalse(planSummary.omitted)
    assertEquals(null, planSummary.omissionReason)
    assertTrue(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Recovered artifacts:"))
  }

  @Test
  fun `compact continuation stays within projection budget and omits private artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001"))
    // The plan is declared phase input and must remain byte-for-byte lossless.
    // The unrelated preplan digest remains private even though it is also large.
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
          "preplan_digest" to mapOf("risk" to "low", "notes" to "y".repeat(8000)),
        ),
      ),
    )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, opened.workflowId),
    )
    val compactMap = WorkflowEngine.compactContinueMap(standard.view.compact)
    val serialized = JsonSupport.mapToJsonString(compactMap)
    val byteSize = serialized.toByteArray(Charsets.UTF_8).size

    assertTrue(
      byteSize < WORKFLOW_INPUT_PROJECTION_BYTE_CEILING,
      "Compact continuation exceeded the declared workflow input projection byte ceiling.",
    )
    assertFalse(serialized.contains("\"step_artifacts\""))
    assertFalse(serialized.contains("\"artifacts\":"))
    assertTrue(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertEquals(null, planSummary.preview)
  }

  @Test
  fun `full continue projection exercises the full shape distinctly from compact`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.TASK_PROSE, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.TASK_PROSE,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
          "preplan_digest" to mapOf("risk" to "low"),
        ),
      ),
    )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_PROSE, opened.workflowId),
    )
    // The explicit diagnostic shape is operator-only: its step_artifacts field
    // stays projected, while its resume snapshot may expose private durable state.
    val fullMap = WorkflowEngine.continueMap(standard.view)
    val fullSerialized = JsonSupport.mapToJsonString(fullMap)

    assertTrue(fullSerialized.contains("\"step_artifacts\""))
    assertTrue(fullSerialized.contains("x".repeat(2000)))
    assertTrue(fullSerialized.contains("preplan_digest"))
  }
}

private fun newService(): WorkflowService = WorkflowService(
  database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  workflowSnapshotValidator = testWorkflowSnapshotValidator,
  decompositionManifestValidator = testDecompositionManifestValidator,
)
