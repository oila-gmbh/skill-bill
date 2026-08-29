package skillbill.application

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.WorkflowService
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.workflow.engine.WorkflowEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The closed-world launch declaration bounds the lossless projection before
 * either the compact or full adapter payload is produced.
 */
private const val WORKFLOW_INPUT_PROJECTION_BYTE_CEILING = 64 * 1024

class WorkflowCompactContinuationTest {
  @Test
  fun `continueWorkflow compact projection inlines small current-step artifacts`() {
    // SKILL-175: the runtime resolves upstream `plan` from the private per-phase records store,
    // not a top-level key. The branch/preplan_digest keys stay unrelated private context.
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "branch" to mapOf("branch_name" to "feat/demo"),
          "preplan_digest" to mapOf("risk" to "low"),
          "feature_task_runtime_phase_records" to mapOf(
            "plan" to completedPlanPhaseRecord(outputArtifact = """{"mode":"implement","task_count":1}"""),
          ),
        ),
      )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
    )
    val compact = standard.view.compact

    assertEquals("reopened", compact.continueStatus)
    assertEquals("reopened", standard.view.continueStatus)
    assertEquals("blocked", compact.workflowStatusBeforeContinue)
    assertEquals("blocked", standard.view.workflowStatusBeforeContinue)
    assertEquals(opened.workflowId, compact.workflowId)
    assertEquals("bill-feature-task", compact.skillName)
    assertEquals("implement", compact.resumeStepId)
    assertEquals("Phase 3: Implement", compact.resumeStepLabel)
    assertEquals(listOf("plan"), compact.requiredArtifactKeys)
    assertEquals(
      listOf("branch", "feature_task_runtime_phase_records", "preplan_digest"),
      compact.availableArtifactKeys,
    )
    assertEquals(
      listOf("branch", "feature_task_runtime_phase_records", "preplan_digest"),
      compact.omittedArtifactKeys,
    )
    assertTrue(compact.continuationBrief.contains(opened.workflowId))
    assertTrue(compact.continuationEntryPrompt.contains("Continue status: reopened"))
    assertTrue(compact.continuationBrief.contains("`current_step_artifacts`"))
    assertTrue(compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertTrue(compact.continuationEntryPrompt.contains("Omitted artifact keys: branch"))
    assertTrue(
      compact.continuationBrief.contains(
        "Omitted artifact keys (branch, feature_task_runtime_phase_records, preplan_digest) remain " +
          "private phase context",
      ),
    )
    assertFalse(compact.continuationBrief.contains("`step_artifacts`"))
    assertFalse(compact.continuationEntryPrompt.contains("Recovered artifacts:"))
    val planSummary = compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertTrue(planSummary.inline)
    assertFalse(planSummary.truncated)
    assertEquals("""{"mode":"implement","task_count":1}""", planSummary.value)
  }

  @Test
  fun `continueWorkflow compact projection bounds large current-step artifacts with a preview`() {
    // The runtime compact view has a hard inline byte ceiling; a large upstream output is never
    // blown into the compact payload. It stays present (the phase record exists), but is omitted
    // with a bounded preview so the operator can inspect it via `workflow show`.
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "preplan_digest" to mapOf("risk" to "low"),
          "feature_task_runtime_phase_records" to mapOf(
            "plan" to completedPlanPhaseRecord(
              outputArtifact = """{"mode":"implement","body":"${"x".repeat(5000)}"}""",
            ),
          ),
        ),
      )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
    )
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }

    assertEquals("reopened", standard.view.continueStatus)
    assertTrue(planSummary.present)
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertEquals(null, planSummary.value)
    assertNotNull(planSummary.preview)
    assertTrue(planSummary.truncated)
    assertTrue(planSummary.omitted)
    assertEquals("artifact_exceeds_inline_limit", planSummary.omissionReason)
    assertTrue(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Recovered artifacts:"))
  }

  @Test
  fun `compact continuation stays within projection budget and omits private artifacts`() {
    // The plan is declared phase input and must stay bounded: it is omitted with a preview, never
    // expanded. The unrelated preplan digest remains private even though it is also large.
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "preplan_digest" to mapOf("risk" to "low", "notes" to "y".repeat(8000)),
          "feature_task_runtime_phase_records" to mapOf(
            "plan" to completedPlanPhaseRecord(
              outputArtifact = """{"mode":"implement","body":"${"x".repeat(12000)}"}""",
            ),
          ),
        ),
      )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
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
    // The 12k-char plan is omitted with a bounded preview; its full body never appears.
    assertFalse(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertNotNull(planSummary.preview)
    assertEquals("artifact_exceeds_inline_limit", planSummary.omissionReason)
  }

  @Test
  fun `full continue projection exercises the full shape distinctly from compact`() {
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
          "preplan_digest" to mapOf("risk" to "low"),
        ),
      )

    val standard = assertIs<WorkflowContinueResult.Standard>(
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
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

/**
 * Opens a blocked `implement` TASK_RUNTIME workflow and applies the given [artifactsPatch] through
 * the same durable update seam every test in this file drives. Returns the service and the opened
 * result so callers can `continueWorkflow` and assert on the compact/full projections.
 */
private fun newBlockedImplementService(
  artifactsPatch: Map<String, Any?>,
): Pair<WorkflowService, WorkflowOpenResult.Ok> {
  val service = newService()
  val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001"))
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = opened.workflowId,
      workflowStatus = "blocked",
      currentStepId = "implement",
      stepUpdates = listOf(
        mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
      ),
      artifactsPatch = artifactsPatch,
    ),
  )
  return service to opened
}

// A durable completed `plan` per-phase record. The runtime resume gate judges upstream presence
// from this private records store, so a blocked `implement` row only reopens once the plan phase
// record is completed here. [outputArtifact] carries the phase output as the durable JSON string
// the compact view resolves the current-step artifact from.
private fun completedPlanPhaseRecord(outputArtifact: String? = null): Map<String, Any?> = linkedMapOf(
  "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
  "record_kind" to "private_phase_record",
  "phase_id" to "plan",
  "status" to "completed",
  "attempt_count" to 1,
  "started_at" to "2026-08-09T10:00:00Z",
  "first_started_at" to "2026-08-09T10:00:00Z",
  "finished_at" to "2026-08-09T10:01:00Z",
  "resolved_agent_id" to "agent-plan",
  "execution_origin" to "agent-executed",
).apply {
  outputArtifact?.let { put("output_artifact", it) }
}
