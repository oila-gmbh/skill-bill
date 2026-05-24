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
    val steps = WorkflowEngine.snapshotView(definition, record).steps

    assertEquals("completed", steps.single { it.stepId == "collect_inputs" }.status)
    assertEquals("completed", steps.single { it.stepId == "gather_diff" }.status)
    assertEquals("running", steps.single { it.stepId == "code_review" }.status)
    assertEquals("pending", steps.single { it.stepId == "verdict" }.status)
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

    assertEquals("done", WorkflowEngine.resumeView(definition, completed).resumeMode)
    assertEquals("recover", WorkflowEngine.resumeView(definition, failed).resumeMode)
  }

  @Test
  fun `verify continuation preserves artifact order and directives`() {
    // SKILL-48 Subtask 2a: `bill-feature-verify` does NOT carry a `blocked`
    // workflow_status (only `bill-feature-implement` does), so this scenario
    // uses workflow_status="running" with a step that has reached the
    // `blocked` step-status. continueDecision reopens the blocked step the
    // same way regardless of whether the surrounding workflow is `running`
    // or terminal.
    val record =
      WorkflowEngine.updateRecord(
        definition,
        WorkflowEngine.openRecord(definition, "wfv-001", "fvr-001", "code_review"),
        WorkflowUpdateInput(
          workflowStatus = "running",
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

    assertEquals("reopened", decision.view.continueStatus)
    assertEquals(
      listOf("criteria_summary", "diff_summary", "review_result", "completeness_audit_result"),
      decision.view.stepArtifactKeys,
    )
    assertTrue(decision.view.continueStepDirective.contains("final verdict"))
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
    assertEquals("recover", WorkflowEngine.resumeView(definition, completedAs("abandoned")).resumeMode)
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
