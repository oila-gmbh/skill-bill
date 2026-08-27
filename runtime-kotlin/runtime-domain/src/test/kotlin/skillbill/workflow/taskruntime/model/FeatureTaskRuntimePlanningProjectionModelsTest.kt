@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.model.SpecSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionModelsTest {
  @Test
  fun `receipt rejects absolute paths, backslashes, dotdot segments, and duplicates`() {
    val baseCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "abc")
    val baseReconciliation = FeatureTaskRuntimeReconciliationEvidence(reconciled = true, evidence = "ok")
    val baseExecuted = listOf(FeatureTaskRuntimeTestExecution("FooTest.kt", FeatureTaskRuntimeTestOutcome.PASSED))

    assertFailsWith<IllegalArgumentException> {
      receipt(listOf("/absolute.kt"), baseCheckpoint, baseReconciliation, baseExecuted)
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(listOf("src\\Foo.kt"), baseCheckpoint, baseReconciliation, baseExecuted)
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(listOf("src/../Foo.kt"), baseCheckpoint, baseReconciliation, baseExecuted)
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(listOf("src/Foo.kt", "src/Foo.kt"), baseCheckpoint, baseReconciliation, baseExecuted)
    }
  }

  @Test
  fun `receipt omits repository_checkpoint from projection fields when absent`() {
    val receipt = FeatureTaskRuntimeImplementationReceipt(
      completedTaskIds = listOf("task-01"),
      changedPaths = listOf("src/Foo.kt"),
      testsExecuted = listOf(FeatureTaskRuntimeTestExecution("FooTest.kt", FeatureTaskRuntimeTestOutcome.PASSED)),
      reconciliationEvidence = FeatureTaskRuntimeReconciliationEvidence(reconciled = true, evidence = "ok"),
    )
    val fieldNames = receipt.toProjectionFields().map { it.name }
    assertFalse(
      fieldNames.contains(FeatureTaskRuntimeImplementationReceipt.FIELD_REPOSITORY_CHECKPOINT),
      fieldNames.toString(),
    )
  }

  @Test
  fun `receipt accepts an empty changed_paths list for a reconciled no-op`() {
    val produced = linkedMapOf<String, Any?>(
      "projection_kind" to "implementation_receipt",
      "contract_version" to FeatureTaskRuntimePlanningProjectionContract.VERSION,
      "completed_task_ids" to listOf("task-01"),
      "changed_paths" to emptyList<String>(),
      "tests_executed" to emptyList<Any?>(),
      "reconciliation_evidence" to linkedMapOf(
        "reconciled" to true,
        "evidence" to "tree already matches target; no edits this turn",
      ),
      "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
    )
    val parsed = assertIs<FeatureTaskRuntimeImplementationReceipt>(
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to produced),
        producingPhaseId = "implement",
        expectedKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
        schemaValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
      ),
    )
    assertEquals(emptyList<String>(), parsed.changedPaths)
  }

  @Test
  fun `an unknown projection kind is rejected at the parse seam`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to linkedMapOf("projection_kind" to "whole_envelope")),
        producingPhaseId = "implement",
        expectedKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
        schemaValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
      )
    }
  }

  @Test
  fun `the producing-phase mapping names only implement and nothing else`() {
    listOf("preplan", "plan").forEach { phaseId ->
      assertNull(
        FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId),
        "phase '$phaseId' delivers prose on the phase-output envelope, not a bounded planning projection",
      )
    }
    assertEquals(
      FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
      FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor("implement"),
    )
    listOf("audit", "implement_fix", "review", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      assertNull(
        FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId),
        "phase '$phaseId' produces no planning projection and must not be gated against one",
      )
    }
  }

  @Test
  fun `a decompose package requires produced_outputs decomposition_package with mode decompose`() {
    assertTrue(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf(
          "produced_outputs" to mapOf(
            "decomposition_package" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>()),
          ),
        ),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf("produced_outputs" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>())),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("mode" to "direct"))),
    )
    assertFalse(featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("steps" to listOf("x")))))
  }

  @Test
  fun `decompose plan outcome decodes only nested decomposition_package`() {
    assertNull(
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf(
          "produced_outputs" to mapOf(
            "value" to "prose plan with leftover decompose mode",
            "mode" to "decompose",
          ),
        ),
        SpecSource.LOCAL,
      ),
    )
    assertNull(
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf("produced_outputs" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>())),
        SpecSource.LOCAL,
      ),
    )
    val outcome = featureTaskRuntimeDecomposePlanOutcomeOrNull(
      mapOf(
        "summary" to "Plan needs ordered subtasks.",
        "produced_outputs" to mapOf("decomposition_package" to nestedDecompositionPackage()),
      ),
      SpecSource.LOCAL,
    )
    assertNotNull(outcome)
    assertEquals("runtime decomposition", outcome.featureName)
  }

  private fun nestedDecompositionPackage(): Map<String, Any?> = mapOf(
    "mode" to "decompose",
    "reason" to "Plan needs ordered subtasks.",
    "feature_name" to "runtime decomposition",
    "parent_spec_overview" to "Split work into subtasks.",
    "validation_strategy" to "bill-code-check",
    "base_branch" to "main",
    "feature_branch" to "feat/decompose",
    "subtasks" to listOf(
      mapOf(
        "id" to 1,
        "name" to "first",
        "scope" to "First subtask.",
        "acceptance_criteria" to listOf("First."),
        "non_goals" to emptyList<String>(),
        "dependency_notes" to "First.",
        "validation_strategy" to "unit tests",
        "next_path" to "Next.",
        "depends_on" to emptyList<Int>(),
      ),
      mapOf(
        "id" to 2,
        "name" to "second",
        "scope" to "Second subtask.",
        "acceptance_criteria" to listOf("Second."),
        "non_goals" to emptyList<String>(),
        "dependency_notes" to "Second.",
        "validation_strategy" to "unit tests",
        "next_path" to "Done.",
        "depends_on" to listOf(1),
      ),
    ),
  )

  private fun receipt(
    changedPaths: List<String>,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
    reconciliation: FeatureTaskRuntimeReconciliationEvidence,
    executed: List<FeatureTaskRuntimeTestExecution>,
  ) = FeatureTaskRuntimeImplementationReceipt(
    completedTaskIds = listOf("task-01"),
    changedPaths = changedPaths,
    testsExecuted = executed,
    reconciliationEvidence = reconciliation,
    repositoryCheckpoint = checkpoint,
  )
}
