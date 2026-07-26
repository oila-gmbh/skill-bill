package skillbill.workflow

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowInputProjectionDeclaration
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowInputProjectionSelectorTest {
  private val engine = WorkflowEngine(AcceptingWorkflowSnapshotValidator)

  @Test
  fun `prose order audits before review and audit excludes review result`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    assertTrue(definition.stepIds.indexOf("implement") < definition.stepIds.indexOf("audit"))
    assertTrue(definition.stepIds.indexOf("audit") < definition.stepIds.indexOf("review"))
    assertTrue(definition.stepIds.indexOf("review") < definition.stepIds.indexOf("validate"))
    assertFalse("review_result" in definition.requiredArtifactsByStep.getValue("audit"))
    assertFalse("review_result" in definition.inputProjectionsByStep.getValue("audit").requiredArtifactKeys)
  }

  @Test
  fun `verification evaluators are independent and verdict accepts receipts only`() {
    val projections = FeatureVerifyWorkflowDefinition.definition.inputProjectionsByStep
    val evaluatorSteps = listOf("feature_flag_audit", "code_review", "unit_test_value_check", "completeness_audit")
    evaluatorSteps.forEach { step ->
      val keys = projections.getValue(step).requiredArtifactKeys
      assertEquals("criteria_summary", keys.first())
      assertTrue("diff_projection" in keys)
      assertFalse(keys.any { it.endsWith("_receipt") || it.endsWith("_result") })
    }
    assertTrue(projections.getValue("verdict").requiredArtifactKeys.all { it.endsWith("_receipt") })
    assertFalse("diff_projection" in projections.getValue("verdict").requiredArtifactKeys)
  }

  @Test
  fun `projection rejects forbidden diagnostics nested inside a typed receipt`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-3", "fis-3", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf(
            "plan" to mapOf("tasks" to listOf("one")),
            "implementation_summary" to mapOf("completed" to true, "progress" to mapOf("heartbeat" to 1)),
            "repository_evidence" to mapOf("fingerprint" to "abc123"),
          ),
          sessionId = "",
        ),
      ),
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1)
    }
  }

  @Test
  fun `fresh and resumed selection are identical and omit private artifacts`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-1", "fis-1", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf(
            "plan" to mapOf("tasks" to listOf("one")),
            "implementation_summary" to mapOf("completed" to true),
            "repository_evidence" to mapOf("checkpoint" to "abc123"),
            "review_result" to mapOf("raw" to "must remain private"),
            "telemetry_payload" to mapOf("tokens" to 99),
          ),
          sessionId = "",
        ),
      ),
    )
    val fresh = engine.launchProjection(definition, snapshot, "audit", 2)
    val resumed = engine.launchProjection(definition, snapshot, "audit", 2)

    assertEquals(fresh, resumed)
    assertEquals(setOf("plan", "implementation_summary", "repository_evidence"), fresh!!.artifacts.keys)
    assertFalse("review_result" in fresh.artifacts)
    assertFalse("telemetry_payload" in fresh.artifacts)
  }

  @Test
  fun `projection budget counts multibyte UTF-8 and rejects without truncation`() {
    val base = FeatureImplementWorkflowDefinition.definition
    val definition = base.copy(
      inputProjectionsByStep = mapOf(
        "audit" to WorkflowInputProjectionDeclaration(
          requiredArtifactKeys = listOf("repository_evidence"),
          forbiddenArtifactKeys = emptySet(),
          maxUtf8Bytes = 40,
          maxCollectionItems = 8,
          repositoryCheckpointArtifactKey = "repository_evidence",
        ),
      ),
    )
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-2", "fis-2", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf("repository_evidence" to "🙂🙂🙂🙂🙂🙂🙂🙂"),
          sessionId = "",
        ),
      ),
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1)
    }
  }

  private object AcceptingWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }
}
