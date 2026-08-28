package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionSchemaValidatorTest {
  @Test
  fun `a legacy implementation receipt is rejected by the reject-all schema`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
          "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `an implementation receipt without repository_checkpoint is still rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to emptyList<Any?>(),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `an unknown projection kind is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "whole_producer_envelope",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "summary" to "narration",
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `an extra forbidden field on a legacy receipt is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to emptyList<Any?>(),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
          "narration" to "planning narration must be rejected",
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `the domain-facing adapter delegates to the canonical reject-all schema`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionValidatorAdapter().validatePlanningProjection(
        producedOutputs = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to emptyList<Any?>(),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
          "progress_diagnostics" to "smuggled whole-envelope field",
        ),
        sourceLabel = "implement#produced_outputs",
      )
    }
    assertEquals("implement#produced_outputs", error.sourceLabel)
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
  }

  @Test
  fun `a mutating receipt with co-resident fields is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
          "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
          "reconciled_state" to linkedMapOf("reconciled" to true, "evidence" to "tree at target"),
          "repair_item_results" to listOf(linkedMapOf("repair_item_id" to "ac-001-gap-1-item-1")),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `an empty changed_paths list on a legacy receipt is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to emptyList<String>(),
          "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
          "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `a violated nested shape on a legacy receipt is rejected without field-path requirements`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true),
          "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `each reject-all reason stays non-blank for multi-field legacy receipts`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "implementation_receipt",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "completed_task_ids" to listOf("task-01"),
          "changed_paths" to listOf("runtime-domain/model/X.kt"),
          "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
          "reconciliation_evidence" to linkedMapOf("reconciled" to true),
          "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
        ),
        sourceLabel = "implement#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertEquals("implement#1", error.sourceLabel)
  }

  @Test
  fun `rejection message carries schema contract identity not body content`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>("projection_kind" to "implementation_receipt"),
        sourceLabel = "implement#1",
      )
    }
    assertEquals("implement#1", error.sourceLabel)
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertTrue(error.message!!.contains("planning projection"), "message must name the contract: ${error.message}")
  }
}
