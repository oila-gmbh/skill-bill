package skillbill.workflow

import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureVerifyWorkflowRuntimeTest {
  private val definition = FeatureVerifyWorkflowDefinition.definition

  @Test
  fun `verify open completes steps before the initial step`() {
    val record = WorkflowEngine.openRecord(definition, "wfv-001", "fvr-001", "code_review")
    val steps = WorkflowEngine.fullPayload(definition, record).steps()

    assertEquals("completed", steps.single { it["step_id"] == "collect_inputs" }["status"])
    assertEquals("completed", steps.single { it["step_id"] == "gather_diff" }["status"])
    assertEquals("running", steps.single { it["step_id"] == "code_review" }["status"])
    assertEquals("pending", steps.single { it["step_id"] == "verdict" }["status"])
  }

  @Test
  fun `verify resume reports done and recover modes`() {
    val running = WorkflowEngine.openRecord(definition, "wfv-001", "fvr-001", "gather_diff")
    val completed =
      WorkflowEngine.updateRecord(
        definition,
        running,
        WorkflowUpdateInput(
          workflowStatus = "completed",
          currentStepId = "finish",
          stepUpdates = listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
          artifactsPatch = mapOf("verdict_result" to mapOf("verdict" to "pass")),
          sessionId = "",
        ),
      )
    val failed = completed.copy(workflowStatus = "failed")

    assertEquals("done", WorkflowEngine.resumePayload(definition, completed)["resume_mode"])
    assertEquals("recover", WorkflowEngine.resumePayload(definition, failed)["resume_mode"])
  }

  @Test
  fun `verify continuation preserves artifact order and directives`() {
    val record =
      WorkflowEngine.updateRecord(
        definition,
        WorkflowEngine.openRecord(definition, "wfv-001", "fvr-001", "code_review"),
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "verdict",
          stepUpdates = listOf(mapOf("step_id" to "verdict", "status" to "blocked", "attempt_count" to 1)),
          artifactsPatch =
          linkedMapOf(
            "review_result" to mapOf("findings" to emptyList<String>()),
            "criteria_summary" to mapOf("criteria" to 3),
            "diff_summary" to mapOf("files" to 4),
            "completeness_audit_result" to mapOf("result" to "pass"),
          ),
          sessionId = "",
        ),
      )

    val decision = WorkflowEngine.continueDecision(definition, record)

    assertEquals("reopened", decision.payload["continue_status"])
    assertEquals(
      listOf("criteria_summary", "diff_summary", "review_result", "completeness_audit_result"),
      decision.payload["step_artifact_keys"],
    )
    assertTrue(decision.payload["continue_step_directive"].toString().contains("final verdict"))
  }

  @Test
  fun `verify validation preserves workflow status contract`() {
    val pending =
      WorkflowUpdateInput(
        workflowStatus = "pending",
        currentStepId = "code_review",
        stepUpdates = listOf(mapOf("step_id" to "code_review", "status" to "failed", "attempt_count" to 1)),
        artifactsPatch = null,
        sessionId = "",
      )
    val abandoned = pending.copy(workflowStatus = "abandoned")

    assertEquals(null, WorkflowEngine.validateUpdate(definition, pending))
    assertEquals(null, WorkflowEngine.validateUpdate(definition, abandoned))
    assertEquals("recover", WorkflowEngine.resumePayload(definition, completedAs("abandoned"))["resume_mode"])
    assertEquals(
      "Invalid workflow_status 'blocked'. Allowed: pending, running, completed, failed, abandoned",
      WorkflowEngine.validateUpdate(definition, pending.copy(workflowStatus = "blocked")),
    )
  }

  @Test
  fun `verify continuation directives preserve oracle text`() {
    assertEquals(
      "Reuse the saved criteria_summary and diff_summary artifacts, pass orchestrated=true to bill-code-review, " +
        "and store the returned telemetry payload with the review result.",
      definition.continuationDirectives["code_review"],
    )
    assertEquals(
      "Reuse the saved review and audit artifacts to produce the final verdict without rerunning earlier steps " +
        "unless recovery made them stale.",
      definition.continuationDirectives["verdict"],
    )
  }

  private fun completedAs(status: String) = WorkflowEngine.updateRecord(
    definition,
    WorkflowEngine.openRecord(definition, "wfv-terminal", "fvr-001", "gather_diff"),
    WorkflowUpdateInput(
      workflowStatus = status,
      currentStepId = "finish",
      stepUpdates = listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
      artifactsPatch = mapOf("verdict_result" to emptyMap<String, Any?>()),
      sessionId = "",
    ),
  )
}

private fun Map<String, Any?>.steps(): List<Map<*, *>> = (this["steps"] as List<*>).map { step -> step as Map<*, *> }
