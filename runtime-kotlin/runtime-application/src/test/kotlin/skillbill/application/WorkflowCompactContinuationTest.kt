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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-64 Subtask 4 (AC1, AC2): payload byte-budget ceilings that catch
 * accidental full-snapshot reintroduction into the compact continuation
 * projection. The compact ceiling is intentionally far below a full
 * snapshot of the same large artifact so a regression that re-inlines the
 * full plan body (several KB) or the durable `step_artifacts` map trips it.
 */
private const val COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING = 8192

class WorkflowCompactContinuationTest {
  @Test
  fun `continueWorkflow compact projection inlines small current-step artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.IMPLEMENT,
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
      service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, opened.workflowId),
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
    assertEquals(listOf("plan", "preplan_digest"), compact.requiredArtifactKeys)
    assertEquals(listOf("branch", "plan", "preplan_digest"), compact.availableArtifactKeys)
    assertEquals(listOf("branch"), compact.omittedArtifactKeys)
    assertTrue(compact.continuationBrief.contains(opened.workflowId))
    assertTrue(compact.continuationEntryPrompt.contains("Continue status: reopened"))
    assertTrue(compact.continuationBrief.contains("`current_step_artifacts`"))
    assertTrue(compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertTrue(compact.continuationEntryPrompt.contains("Omitted artifact keys: branch"))
    assertTrue(compact.continuationBrief.contains("Omitted artifact keys (branch) require read-only inspection"))
    assertFalse(compact.continuationBrief.contains("`step_artifacts`"))
    assertFalse(compact.continuationEntryPrompt.contains("Recovered artifacts:"))
    val planSummary = compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertTrue(planSummary.inline)
    assertFalse(planSummary.truncated)
    assertEquals(mapOf("mode" to "implement", "task_count" to 1), planSummary.value)
  }

  @Test
  fun `continueWorkflow compact projection summarizes large current-step artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.IMPLEMENT,
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
      service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, opened.workflowId),
    )
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }

    assertTrue(planSummary.present)
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertNull(planSummary.value)
    assertEquals(1024, requireNotNull(planSummary.preview).length)
    assertTrue(planSummary.truncated)
    assertTrue(planSummary.omitted)
    assertEquals("artifact_exceeds_inline_limit", planSummary.omissionReason)
    assertTrue(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Recovered artifacts:"))
  }

  @Test
  fun `compact continuation payload stays under byte ceiling and omits full snapshot for large artifacts`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    // Representative LARGE current-step artifact: a multi-KB plan body that a
    // full snapshot would inline verbatim. The compact projection must summarize
    // it instead, so the serialized compact payload stays well under the ceiling.
    service.update(
      WorkflowFamilyKind.IMPLEMENT,
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
      service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, opened.workflowId),
    )
    // Two large artifacts (plan + preplan_digest) summed are ~20KB raw; a full
    // snapshot would inline all of it. The compact projection bounds each to a
    // 1024-char preview, so the whole payload stays under the ceiling.
    val compactMap = WorkflowEngine.compactContinueMap(standard.view.compact)
    val serialized = JsonSupport.mapToJsonString(compactMap)
    val byteSize = serialized.toByteArray(Charsets.UTF_8).size

    assertTrue(
      byteSize < COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING,
      "Compact continuation payload was $byteSize bytes, exceeding the " +
        "$COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING ceiling; a full snapshot was likely reintroduced.",
    )
    // Full-snapshot / full-continue markers must never leak into the compact wire
    // shape. `step_artifacts` is the full-continue durable artifacts map; the raw
    // large bodies must not appear inline.
    assertFalse(serialized.contains("\"step_artifacts\""))
    assertFalse(serialized.contains("\"artifacts\":"))
    assertFalse(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    // The compact summary still records the artifact exists and is large.
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertEquals(1024, requireNotNull(planSummary.preview).length)
  }

  @Test
  fun `full continue projection exercises the full shape distinctly from compact`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.IMPLEMENT,
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
      service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, opened.workflowId),
    )
    // The explicit full/debug continue projection (the read-only show fallback
    // shape) carries the full durable `step_artifacts` map with the large body
    // inline — the opposite of the compact projection. This pins that the two
    // shapes diverge and the full shape is exercised distinctly.
    val fullMap = WorkflowEngine.continueMap(standard.view)
    val fullSerialized = JsonSupport.mapToJsonString(fullMap)

    assertTrue(fullSerialized.contains("\"step_artifacts\""))
    assertTrue(fullSerialized.contains("x".repeat(2000)))
    assertTrue(
      fullSerialized.toByteArray(Charsets.UTF_8).size >= COMPACT_CONTINUATION_PAYLOAD_BYTE_CEILING,
      "The full continue projection should be materially larger than the compact ceiling.",
    )
  }
}

private fun newService(): WorkflowService = WorkflowService(
  database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  workflowSnapshotValidator = testWorkflowSnapshotValidator,
  decompositionManifestValidator = testDecompositionManifestValidator,
)
