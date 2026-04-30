package skillbill.workflow

import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowUpdateInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureImplementWorkflowRuntimeTest {
  private val definition = FeatureImplementWorkflowDefinition.definition

  @Test
  fun `implement open starts only the requested step`() {
    val record = WorkflowEngine.openRecord(definition, "wfl-001", "fis-001", "plan")
    val payload = WorkflowEngine.fullPayload(definition, record)
    val steps = payload.steps()

    assertEquals("bill-feature-implement", payload["workflow_name"])
    assertEquals("plan", payload["current_step_id"])
    assertEquals("running", steps.single { it["step_id"] == "plan" }["status"])
    assertTrue(steps.filterNot { it["step_id"] == "plan" }.all { it["status"] == "pending" })
  }

  @Test
  fun `implement update merges steps in stable order and preserves artifact patches`() {
    val opened = WorkflowEngine.openRecord(definition, "wfl-001", "fis-001", "assess")
    val updated =
      WorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "implement",
          stepUpdates =
          listOf(
            mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
            mapOf("step_id" to "assess", "status" to "completed", "attempt_count" to 1),
          ),
          artifactsPatch = mapOf("assessment" to mapOf("feature_name" to "workflow-runtime")),
          sessionId = "",
        ),
      )

    val payload = WorkflowEngine.fullPayload(definition, updated)
    val steps = payload.steps()
    val artifacts = payload["artifacts"] as Map<*, *>
    assertEquals(definition.stepIds, steps.map { it["step_id"] })
    assertEquals("completed", steps.first()["status"])
    assertEquals(mapOf("feature_name" to "workflow-runtime"), artifacts["assessment"])
  }

  @Test
  fun `implement continue blocks missing artifacts and reopens blocked step`() {
    val opened = WorkflowEngine.openRecord(definition, "wfl-001", "fis-001", "assess")
    val blocked =
      WorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "implement",
          stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1)),
          artifactsPatch = mapOf("preplan_digest" to mapOf("ok" to true)),
          sessionId = "",
        ),
      )

    val blockedDecision = WorkflowEngine.continueDecision(definition, blocked)
    assertEquals("blocked", blockedDecision.payload["continue_status"])
    assertEquals(listOf("plan"), blockedDecision.payload["missing_artifacts"])
    assertFalse(blockedDecision.shouldReopen)

    val resumable =
      WorkflowEngine.updateRecord(
        definition,
        blocked,
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "implement",
          stepUpdates = null,
          artifactsPatch = mapOf("plan" to mapOf("task_count" to 13)),
          sessionId = "",
        ),
      )
    val reopened = WorkflowEngine.continueDecision(definition, resumable)
    assertEquals("reopened", reopened.payload["continue_status"])
    assertTrue(reopened.shouldReopen)
    assertEquals(2, reopened.nextAttemptCount)
  }

  @Test
  fun `implement validation preserves Python workflow status contract`() {
    val pending =
      WorkflowUpdateInput(
        workflowStatus = "pending",
        currentStepId = "assess",
        stepUpdates = listOf(mapOf("step_id" to "assess", "status" to "failed", "attempt_count" to 1)),
        artifactsPatch = null,
        sessionId = "",
      )
    val abandoned = pending.copy(workflowStatus = "abandoned")

    assertEquals(null, WorkflowEngine.validateUpdate(definition, pending))
    assertEquals(null, WorkflowEngine.validateUpdate(definition, abandoned))
    assertEquals("recover", WorkflowEngine.resumePayload(definition, completedAs("abandoned"))["resume_mode"])
    assertEquals(
      "Invalid workflow_status 'canceled'. Allowed: pending, running, completed, failed, abandoned, blocked",
      WorkflowEngine.validateUpdate(definition, pending.copy(workflowStatus = "canceled")),
    )
  }

  @Test
  fun `implement continuation directives preserve Python oracle text`() {
    assertEquals(
      "Do not rerun Step 1 discovery. Reuse the saved assessment artifact, create or verify the feature branch, " +
        "persist the branch artifact, then continue into preplan.",
      definition.continuationDirectives["create_branch"],
    )
    assertEquals(
      "Do not re-execute work. Close the workflow cleanly by inspecting pr_result and final telemetry state, then " +
        "emit only the terminal summary if anything is still missing.",
      definition.continuationDirectives["finish"],
    )
  }

  @Test
  fun `implement planning step supports terminal decomposition branch`() {
    assertEquals("Step 3: Create Implementation Plan or Decompose", definition.stepLabels["plan"])
    assertEquals(
      "Re-run the planning phase using assessment and preplan_digest. Persist either the implementation plan " +
        "or the terminal decomposition package.",
      definition.resumeActions["plan"],
    )
    assertEquals(
      "Skip the discovery steps. Reuse the saved assessment and preplan_digest artifacts, then spawn the planning " +
        "subagent from that recovered context. If it returns mode: \"decompose\", persist the subtask specs and " +
        "close the workflow at planning instead of proceeding to implementation.",
      definition.continuationDirectives["plan"],
    )
  }

  private fun completedAs(status: String) = WorkflowEngine.updateRecord(
    definition,
    WorkflowEngine.openRecord(definition, "wfl-terminal", "fis-001", "assess"),
    WorkflowUpdateInput(
      workflowStatus = status,
      currentStepId = "finish",
      stepUpdates = listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
      artifactsPatch = mapOf("pr_result" to emptyMap<String, Any?>()),
      sessionId = "",
    ),
  )
}

private fun Map<String, Any?>.steps(): List<Map<*, *>> = (this["steps"] as List<*>).map { step -> step as Map<*, *> }
