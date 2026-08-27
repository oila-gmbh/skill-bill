package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionSchemaValidatorTest {
  @Test
  fun `a valid implementation receipt validates`() {
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

  @Test
  fun `an implementation receipt without repository_checkpoint validates`() {
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
    assertTrue(error.sourceLabel == "implement#1")
  }

  @Test
  fun `an extra forbidden field on a variant is rejected`() {
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
    assertTrue(
      error.reason.contains("narration"),
      "the reported violation must name the offending field: ${error.reason}",
    )
  }

  @Test
  fun `the domain-facing adapter delegates to the canonical schema`() {
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
    assertTrue(
      error.reason.contains("progress_diagnostics"),
      "the adapter must surface the canonical schema's additionalProperties violation: ${error.reason}",
    )
  }

  @Test
  fun `a mutating receipt keeps its governed co-resident produced_outputs fields`() {
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

  @Test
  fun `an empty changed_paths list on a receipt is accepted by the schema`() {
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

  @Test
  fun `a violated instance location is reported exactly once`() {
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

    val location = "\$.reconciliation_evidence"
    assertEquals(
      1,
      Regex(Regex.escape(location)).findAll(error.reason).count(),
      "the violated location must appear once, not once per formatting layer: ${error.reason}",
    )
    assertContains(error.reason, "required property 'evidence' not found")
  }

  @Test
  fun `each location in a multi-violation reason is reported once`() {
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

    error.reason.removeSuffix(" more)").split(" | ").forEach { segment ->
      val locations = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""")
        .findAll(segment)
        .map(MatchResult::value)
        .filter { it.isNotBlank() }
        .toList()
      assertEquals(
        locations.distinct().size,
        locations.size,
        "a reported violation repeated its instance location: $segment",
      )
    }
  }

  @Test
  fun `rejection message carries schema locations not body content`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>("projection_kind" to "implementation_receipt"),
        sourceLabel = "implement#1",
      )
    }
    assertEquals("implement#1", error.sourceLabel)
    assertTrue(error.message!!.contains("planning projection"), "message must name the contract: ${error.message}")
  }
}
