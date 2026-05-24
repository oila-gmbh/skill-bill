package skillbill.workflow

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowUpdateInput
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureImplementWorkflowRuntimeTest {
  private val definition = FeatureImplementWorkflowDefinition.definition

  @Test
  fun `implement open starts only the requested step`() {
    val record = WorkflowEngine.openRecord(definition, "wfl-001", "fis-001", "plan")
    val snapshot = WorkflowEngine.snapshotView(definition, record)

    assertEquals("bill-feature-implement", snapshot.workflowName)
    assertEquals("plan", snapshot.currentStepId)
    assertEquals("running", snapshot.steps.single { it.stepId == "plan" }.status)
    assertTrue(snapshot.steps.filterNot { it.stepId == "plan" }.all { it.status == "pending" })
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

    val snapshot = WorkflowEngine.snapshotView(definition, updated)
    val steps = snapshot.steps
    val artifacts = snapshot.artifacts
    assertEquals(definition.stepIds, steps.map { it.stepId })
    assertEquals("completed", steps.first().status)
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
    assertEquals("blocked", blockedDecision.view.continueStatus)
    assertEquals(listOf("plan"), blockedDecision.view.resume.missingArtifacts)
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
    assertEquals("reopened", reopened.view.continueStatus)
    assertTrue(reopened.shouldReopen)
    assertEquals(2, reopened.nextAttemptCount)
  }

  @Test
  fun `implement continue rejects persisted oversized attempt count`() {
    val persisted = WorkflowEngine.openRecord(definition, "wfl-oversized", "fis-001", "assess").copy(
      workflowStatus = "running",
      currentStepId = "implement",
      stepsJson = """[{"step_id":"implement","status":"blocked","attempt_count":2147483648}]""",
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowEngine.continueDecision(definition, persisted)
    }

    assertContains(error.message.orEmpty(), "attempt_count")
  }

  @Test
  fun `implement read seam rejects malformed durable steps JSON`() {
    val persisted = WorkflowEngine.openRecord(definition, "wfl-bad-steps", "fis-001", "assess").copy(
      stepsJson = """[{"step_id":"assess"}""",
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowEngine.snapshotView(definition, persisted)
    }

    assertContains(error.message.orEmpty(), "stepsJson")
    assertContains(error.message.orEmpty(), "malformed JSON")
  }

  @Test
  fun `implement read seam rejects non-array durable steps JSON`() {
    val persisted = WorkflowEngine.openRecord(definition, "wfl-object-steps", "fis-001", "assess").copy(
      stepsJson = """{"step_id":"assess","status":"running","attempt_count":1}""",
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowEngine.snapshotView(definition, persisted)
    }

    assertContains(error.message.orEmpty(), "stepsJson")
    assertContains(error.message.orEmpty(), "JSON array")
  }

  @Test
  fun `implement read seam rejects malformed durable artifacts JSON`() {
    val persisted = WorkflowEngine.openRecord(definition, "wfl-bad-artifacts", "fis-001", "assess").copy(
      artifactsJson = """{"assessment":""",
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowEngine.snapshotView(definition, persisted)
    }

    assertContains(error.message.orEmpty(), "artifactsJson")
    assertContains(error.message.orEmpty(), "malformed JSON")
  }

  @Test
  fun `implement read seam rejects non-object durable artifacts JSON`() {
    val persisted = WorkflowEngine.openRecord(definition, "wfl-array-artifacts", "fis-001", "assess").copy(
      artifactsJson = """["assessment"]""",
    )

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowEngine.snapshotView(definition, persisted)
    }

    assertContains(error.message.orEmpty(), "artifactsJson")
    assertContains(error.message.orEmpty(), "JSON object")
  }

  @Test
  fun `implement read seam rejects blank persisted workflow contract fields`() {
    val baseline = WorkflowEngine.openRecord(definition, "wfl-blank-contract", "fis-001", "assess")
    val blankSnapshots = listOf(
      "workflow_name" to baseline.copy(workflowName = ""),
      "contract_version" to baseline.copy(contractVersion = ""),
      "workflow_status" to baseline.copy(workflowStatus = ""),
    )

    blankSnapshots.forEach { (fieldName, persisted) ->
      val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
        WorkflowEngine.snapshotView(definition, persisted)
      }
      assertContains(error.message.orEmpty(), fieldName)
    }
  }

  @Test
  fun `implement validation preserves workflow status contract`() {
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
    assertEquals("recover", WorkflowEngine.resumeView(definition, completedAs("abandoned")).resumeMode)
    assertEquals(
      "Invalid workflow_status 'canceled'. Allowed: pending, running, completed, failed, abandoned, blocked",
      WorkflowEngine.validateUpdate(definition, pending.copy(workflowStatus = "canceled")),
    )
  }

  @Test
  fun `implement validation rejects non exact integer attempt counts`() {
    val valid =
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "assess",
        stepUpdates = listOf(mapOf("step_id" to "assess", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = null,
        sessionId = "",
      )
    val expected = "step_updates[0].attempt_count must be an integer >= 0."

    assertEquals(null, WorkflowEngine.validateUpdate(definition, valid))
    assertEquals(
      expected,
      WorkflowEngine.validateUpdate(
        definition,
        valid.copy(
          stepUpdates = listOf(mapOf("step_id" to "assess", "status" to "running", "attempt_count" to 1.5)),
        ),
      ),
    )
    assertEquals(
      expected,
      WorkflowEngine.validateUpdate(
        definition,
        valid.copy(
          stepUpdates = listOf(
            mapOf("step_id" to "assess", "status" to "running", "attempt_count" to 4_294_967_297L),
          ),
        ),
      ),
    )
  }

  @Test
  fun `implement continuation directives preserve oracle text`() {
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
