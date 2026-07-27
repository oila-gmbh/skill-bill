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
  private val engine = WorkflowEngine(AcceptingWorkflowSnapshotValidator) { "abc123" }

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
  fun `fresh and resumed review projections use review scope as repository checkpoint`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val artifacts = mapOf(
      "acceptance_criteria" to mapOf("criteria" to listOf("AC-1")),
      "review_scope" to mapOf(
        "fingerprint" to "abc123",
        "comparison_scope" to "base..head",
        "changed_paths" to listOf("Changed.kt"),
      ),
      "audit_clearance" to mapOf("contract_version" to "0.1", "verdict" to "approved"),
    )
    val opened = engine.openRecord(definition, "wfl-review", "fis-review", "review")
    val updated = engine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "review",
        stepUpdates = null,
        artifactsPatch = artifacts,
        sessionId = "",
      ),
    )
    val resumed = engine.updateRecord(
      definition,
      updated,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "review",
        stepUpdates = null,
        artifactsPatch = emptyMap(),
        sessionId = "",
      ),
    )

    listOf(updated, resumed).forEachIndexed { index, record ->
      val projection = WorkflowInputProjectionSelector.select(
        definition,
        engine.snapshotView(definition, record),
        "review",
        index + 1,
        "abc123",
      )

      assertEquals("abc123", (projection.repositoryCheckpoint as Map<*, *>)["fingerprint"])
      assertEquals(artifacts.keys, projection.artifacts.keys)
    }
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
    assertTrue(
      projections.getValue("verdict").requiredArtifactKeys
        .filterNot { it == "diff_projection" }
        .all { it.endsWith("_receipt") },
    )
    assertTrue("diff_projection" in projections.getValue("verdict").requiredArtifactKeys)
    projections.values.forEach { declaration ->
      declaration.requiredArtifactKeys.forEach { key ->
        assertTrue(
          declaration.projectedFieldsByArtifactKey.getValue(key).isNotEmpty(),
          "verification projection must declare typed fields for $key",
        )
      }
    }
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
    val receipt = implementationReceipt() + mapOf(
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
  fun `projection supplies runtime owned repository evidence when producers omit it`() {
    val definition = FeatureImplementWorkflowDefinition.definition
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
            "implementation_summary" to implementationReceipt(),
          ),
          sessionId = "",
        ),
      ),
    )

    val projection = WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "current")
    assertEquals(mapOf("fingerprint" to "current"), projection.artifacts["repository_evidence"])
  }

  @Test
  fun `projection replaces producer claimed repository evidence with runtime identity`() {
    val definition = FeatureImplementWorkflowDefinition.definition
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
            "implementation_summary" to implementationReceipt(),
            "repository_evidence" to mapOf("fingerprint" to "supplied"),
          ),
          sessionId = "",
        ),
      ),
    )

    val projection = WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "runtime-resolved")
    assertEquals(mapOf("fingerprint" to "runtime-resolved"), projection.artifacts["repository_evidence"])
  }

  @Test
  fun `every receipt bearing prose projection declares typed fields`() {
    val projections = FeatureImplementWorkflowDefinition.definition.inputProjectionsByStep
    val receiptKeys = setOf(
      "implementation_summary",
      "audit_clearance",
      "validation_request",
      "validation_receipt",
      "boundary_candidates",
      "history_receipt",
      "commit_request",
      "commit_receipt",
      "pr_request",
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
  fun `prose finalization uses least context requests and receipts`() {
    val projections = FeatureImplementWorkflowDefinition.definition.inputProjectionsByStep
    assertEquals(
      setOf("validation_request", "audit_clearance", "repository_evidence"),
      projections.getValue("validate").requiredArtifactKeys.toSet(),
    )
    assertEquals(
      setOf("boundary_candidates", "validation_receipt", "repository_evidence"),
      projections.getValue("write_history").requiredArtifactKeys.toSet(),
    )
    assertEquals(
      setOf("commit_request", "validation_receipt", "history_receipt", "repository_evidence"),
      projections.getValue("commit_push").requiredArtifactKeys.toSet(),
    )
    assertEquals(
      setOf("pr_request", "commit_receipt", "repository_evidence"),
      projections.getValue("pr_description").requiredArtifactKeys.toSet(),
    )
    val forbiddenLegacyArtifacts = setOf(
      "audit_report",
      "review_result",
      "implementation_summary",
      "validation_result",
      "history_result",
    )
    listOf("validate", "write_history", "commit_push", "pr_description").forEach { stepId ->
      assertTrue(
        projections.getValue(stepId).requiredArtifactKeys.none { it in forbiddenLegacyArtifacts },
        "$stepId must not receive private legacy phase artifacts",
      )
    }
  }

  @Test
  fun `fresh and resumed selection are identical and omit private artifacts`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val receipt = implementationReceipt()
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
          "review_result" to mapOf("raw" to "must remain private"),
          "telemetry_payload" to mapOf("tokens" to 99),
        ),
        sessionId = "",
      ),
    )
    val fresh = engine.freshLaunchProjection(definition, record, "audit", 0)
    val resumed = engine.continueDecision(definition, record).view.stepArtifacts

    assertEquals(fresh!!.artifacts, resumed)
    assertEquals(setOf("plan", "implementation_summary", "repository_evidence"), fresh.artifacts.keys)
    assertFalse("review_result" in fresh.artifacts)
    assertFalse("telemetry_payload" in fresh.artifacts)
  }

  @Test
  fun `engine resolves checkpoint independently for fresh and resumed launches`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    val record = engine.updateRecord(
      definition,
      engine.openRecord(definition, "wfl-independent", "fis-independent", "audit"),
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = null,
        artifactsPatch = mapOf(
          "plan" to mapOf("tasks" to listOf("one")),
          "implementation_summary" to implementationReceipt(),
          "repository_evidence" to mapOf("fingerprint" to "supplied"),
        ),
        sessionId = "",
      ),
    )

    val fresh = engine.freshLaunchProjection(definition, record, "audit", 0)
    val resumed = engine.continueDecision(definition, record).view.stepArtifacts
    assertEquals(mapOf("fingerprint" to "abc123"), fresh!!.artifacts["repository_evidence"])
    assertEquals(fresh.artifacts, resumed)
  }

  @Test
  fun `finalization receipts remain valid after repository checkpoint changes`() {
    val definition = FeatureImplementWorkflowDefinition.definition
    listOf("commit_push", "pr_description").forEach { stepId ->
      val receiptFields = definition.inputProjectionsByStep.getValue(stepId).projectedFieldsByArtifactKey.values
      assertTrue(receiptFields.none { "repository_checkpoint" in it })
    }
  }

  @Test
  fun `projection budget counts multibyte UTF-8 and rejects without truncation`() {
    val base = FeatureImplementWorkflowDefinition.definition
    val definition = base.copy(
      inputProjectionsByStep = mapOf(
        "audit" to WorkflowInputProjectionDeclaration(
          requiredArtifactKeys = listOf("repository_evidence"),
          forbiddenArtifactKeys = emptySet(),
          maxUtf8Bytes = 30,
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
          artifactsPatch = emptyMap(),
          sessionId = "",
        ),
      ),
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      WorkflowInputProjectionSelector.select(definition, snapshot, "audit", 1, "🙂🙂🙂🙂🙂🙂🙂🙂")
    }
  }

  private object AcceptingWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }

  private fun implementationReceipt(): Map<String, Any?> = mapOf(
    "tasks_completed" to 1,
    "files_created" to emptyList<String>(),
    "files_modified" to listOf("Changed.kt"),
    "tests_written" to emptyList<String>(),
    "plan_deviation_notes" to "",
    "criteria_to_file_map" to mapOf("1" to listOf("Changed.kt")),
    "notes_for_review" to "",
    "stopped_early" to false,
    "stopped_reason" to "",
  )
}
