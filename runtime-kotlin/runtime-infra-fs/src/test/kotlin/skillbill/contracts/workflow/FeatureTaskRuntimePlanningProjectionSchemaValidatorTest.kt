package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionSchemaValidatorTest {
  @Test
  fun `a valid preplanning digest validates`() {
    FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
      payload = linkedMapOf<String, Any?>(
        "projection_kind" to "preplanning_digest",
        "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
        "affected_boundaries" to listOf("runtime-domain/model"),
        "risks" to listOf("producer may omit digest fields"),
        "rollout" to linkedMapOf("flag_required" to false, "notes" to "no flag needed"),
        "validation_strategy" to listOf("snapshot projection tests"),
      ),
      sourceLabel = "preplan#1",
    )
  }

  @Test
  fun `a valid executable plan validates`() {
    FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
      payload = linkedMapOf<String, Any?>(
        "projection_kind" to "executable_plan",
        "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
        "mode" to "direct",
        "tasks" to listOf(
          linkedMapOf(
            "task_id" to "task-01",
            "depends_on" to emptyList<String>(),
            "description" to "add contract",
            "criterion_refs" to listOf("AC-005"),
            "test_obligations" to listOf("parity test"),
            "constraints" to listOf("draft 2020-12"),
          ),
        ),
        "validation_strategy" to listOf("focused gradle"),
      ),
      sourceLabel = "plan#1",
    )
  }

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
  fun `an unknown projection kind is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "whole_producer_envelope",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "summary" to "narration",
        ),
        sourceLabel = "plan#1",
      )
    }
    assertTrue(error.reason.isNotBlank(), "rejection reason must be non-empty")
    assertTrue(error.sourceLabel == "plan#1")
  }

  @Test
  fun `an extra forbidden field on a variant is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
        payload = linkedMapOf<String, Any?>(
          "projection_kind" to "executable_plan",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "mode" to "direct",
          "tasks" to listOf(
            linkedMapOf(
              "task_id" to "task-01",
              "depends_on" to emptyList<String>(),
              "description" to "add contract",
              "criterion_refs" to listOf("AC-005"),
              "test_obligations" to listOf("parity test"),
              "constraints" to listOf("draft 2020-12"),
            ),
          ),
          "validation_strategy" to listOf("focused gradle"),
          "narration" to "planning narration must be rejected",
        ),
        sourceLabel = "plan#1",
      )
    }
    assertTrue(
      error.reason.contains("narration"),
      "the reported violation must name the offending field: ${error.reason}",
    )
  }

  @Test
  fun `the domain-facing adapter delegates to the canonical schema`() {
    // F-001: the validator had no production caller, so additionalProperties:false, the compactSummary
    // anti-paste patterns and every required list were dead at runtime. The domain parse seam now goes
    // through this port, so the adapter must actually reach the canonical schema.
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimePlanningProjectionValidatorAdapter().validatePlanningProjection(
        producedOutputs = linkedMapOf<String, Any?>(
          "projection_kind" to "preplanning_digest",
          "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
          "affected_boundaries" to listOf("runtime-domain/model"),
          "risks" to listOf("producer may omit digest fields"),
          "rollout" to linkedMapOf("flag_required" to false, "notes" to "no flag needed"),
          "validation_strategy" to listOf("snapshot projection tests"),
          "progress_diagnostics" to "smuggled whole-envelope field",
        ),
        sourceLabel = "preplan#produced_outputs",
      )
    }
    assertEquals("preplan#produced_outputs", error.sourceLabel)
    assertTrue(
      error.reason.contains("progress_diagnostics"),
      "the adapter must surface the canonical schema's additionalProperties violation: ${error.reason}",
    )
  }

  @Test
  fun `a mutating receipt keeps its governed co-resident produced_outputs fields`() {
    // reconciled_state and repair_item_results are required of a mutating phase by the phase-output and
    // audit-repair contracts, so additionalProperties:false must not reject an output that carries them.
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
  fun `a plan task without optional depends_on or constraints validates`() {
    FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
      payload = linkedMapOf<String, Any?>(
        "projection_kind" to "executable_plan",
        "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
        "mode" to "direct",
        "tasks" to listOf(
          linkedMapOf(
            "task_id" to "task-01",
            "description" to "add contract",
            "criterion_refs" to listOf("AC-005"),
            "test_obligations" to listOf("parity test"),
          ),
        ),
        "validation_strategy" to listOf("focused gradle"),
      ),
      sourceLabel = "plan#1",
    )
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
