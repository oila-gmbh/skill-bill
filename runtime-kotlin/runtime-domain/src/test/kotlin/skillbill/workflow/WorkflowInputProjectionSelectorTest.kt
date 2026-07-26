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
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "abc123")
    }
  }

  @Test
  fun `audit projection selects declared implementation receipt fields only`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val receipt = mapOf(
      "projection_kind" to "implementation_receipt",
      "contract_version" to "0.1",
      "completed_task_ids" to listOf("task-1"),
      "changed_paths" to listOf("Changed.kt"),
      "tests_added" to emptyList<String>(),
      "tests_updated" to emptyList<String>(),
      "deviations" to emptyList<Map<String, String>>(),
      "unresolved_items" to emptyList<String>(),
      "repository_checkpoint" to mapOf("fingerprint" to "abc123"),
      "private_diagnostic" to "must not be delivered",
    )
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-typed", "fis-typed", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf(
            "plan" to mapOf("tasks" to listOf("one")),
            "implementation_summary" to receipt,
            "repository_evidence" to mapOf("fingerprint" to "abc123"),
          ),
          sessionId = "",
        ),
      ),
    )

    val projection = WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "abc123")
    val projectedReceipt = projection.artifacts.getValue("implementation_summary") as Map<*, *>
    assertFalse("private_diagnostic" in projectedReceipt)
    assertEquals(
      definition.inputProjectionsByStep.getValue("audit")
        .projectedFieldsByArtifactKey.getValue("implementation_summary"),
      projectedReceipt.keys,
    )
  }

  @Test
  fun `projection rejects receipt checkpoint stale against authoritative repository evidence`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val requiredReceipt = definition.inputProjectionsByStep.getValue("audit")
      .projectedFieldsByArtifactKey.getValue("implementation_summary")
      .associateWith { field ->
        when (field) {
          "projection_kind" -> "implementation_receipt"
          "contract_version" -> "0.1"
          "repository_checkpoint" -> mapOf("fingerprint" to "stale")
          else -> emptyList<String>()
        }
      }
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-stale", "fis-stale", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf(
            "plan" to mapOf("tasks" to listOf("one")),
            "implementation_summary" to requiredReceipt,
            "repository_evidence" to mapOf("fingerprint" to "current"),
          ),
          sessionId = "",
        ),
      ),
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "current")
    }
  }

  @Test
  fun `projection rejects supplied checkpoint evidence stale against runtime resolved checkpoint`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val receipt = definition.inputProjectionsByStep.getValue("audit")
      .projectedFieldsByArtifactKey.getValue("implementation_summary")
      .associateWith { field ->
        when (field) {
          "projection_kind" -> "implementation_receipt"
          "contract_version" -> "0.1"
          "repository_checkpoint" -> mapOf("fingerprint" to "supplied")
          else -> emptyList<String>()
        }
      }
    val snapshot = engine.snapshotView(
      definition,
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfl-runtime-stale", "fis-runtime-stale", "audit"),
        skillbill.workflow.model.WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "audit",
          stepUpdates = null,
          artifactsPatch = mapOf(
            "plan" to mapOf("tasks" to listOf("one")),
            "implementation_summary" to receipt,
            "repository_evidence" to mapOf("fingerprint" to "supplied"),
          ),
          sessionId = "",
        ),
      ),
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "runtime-resolved")
    }
  }

  @Test
  fun `every receipt bearing prose projection declares typed fields`() {
    val projections = FeatureImplementWorkflowDefinition.definition.inputProjectionsByStep
    val receiptKeys = setOf(
      "implementation_summary",
      "audit_report",
      "review_result",
      "validation_result",
      "history_result",
    )

    projections.forEach { (stepId, declaration) ->
      declaration.requiredArtifactKeys.intersect(receiptKeys).forEach { receiptKey ->
        assertTrue(
          declaration.projectedFieldsByArtifactKey.getValue(receiptKey).isNotEmpty(),
          "$stepId must declare bounded fields for $receiptKey",
        )
      }
    }
  }

  @Test
  fun `fresh and resumed selection are identical and omit private artifacts`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val receipt = definition.inputProjectionsByStep.getValue("audit")
      .projectedFieldsByArtifactKey.getValue("implementation_summary")
      .associateWith { field ->
        when (field) {
          "projection_kind" -> "implementation_receipt"
          "contract_version" -> "0.1"
          "repository_checkpoint" -> mapOf("fingerprint" to "abc123")
          else -> emptyList<String>()
        }
      }
    val record = engine.updateRecord(
      definition,
      engine.openRecord(definition, "wfl-1", "fis-1", "audit"),
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = null,
        artifactsPatch = mapOf(
          "plan" to mapOf("tasks" to listOf("one")),
          "implementation_summary" to receipt,
          "repository_evidence" to mapOf("checkpoint" to "abc123"),
          "review_result" to mapOf("raw" to "must remain private"),
          "telemetry_payload" to mapOf("tokens" to 99),
        ),
        sessionId = "",
      ),
    )
    val snapshot = engine.snapshotView(definition, record)
    val fresh = engine.launchProjection(definition, snapshot, "audit", 0, "abc123")
    val resumed = engine.continueDecision(definition, record).view.stepArtifacts

    assertEquals(fresh!!.artifacts, resumed)
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
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "abc123")
    }
  }

  private object AcceptingWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }
}
