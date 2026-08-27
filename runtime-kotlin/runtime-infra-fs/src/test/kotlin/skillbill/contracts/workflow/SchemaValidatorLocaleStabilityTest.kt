package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaValidatorLocaleStabilityTest {
  @Test
  fun `reject-all planning projection still yields English-ish non-blank reason under GERMANY locale`() {
    withDefaultLocale(Locale.GERMANY) {
      val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
        FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
          payload = legacyReceiptPayload(),
          sourceLabel = "implement#produced_outputs",
        )
      }
      assertEquals("implement#produced_outputs", error.sourceLabel)
      assertTrue(error.reason.isNotBlank(), "reject-all reason must stay non-blank under host locale")
      assertTrue(
        error.reason.any { it in 'A'..'Z' || it in 'a'..'z' },
        "locale-stable validator must keep an English-ish reason: ${error.reason}",
      )
    }
  }

  @Test
  fun `reject-all sourceLabel is preserved under any host locale`() {
    withDefaultLocale(Locale.GERMANY) {
      val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
        FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
          payload = legacyReceiptPayload(),
          sourceLabel = "implement#produced_outputs",
        )
      }
      assertEquals("implement#produced_outputs", error.sourceLabel)
      assertTrue(error.reason.isNotBlank())
    }
  }

  private fun legacyReceiptPayload(): Map<String, Any?> = linkedMapOf(
    "projection_kind" to "implementation_receipt",
    "contract_version" to FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION,
    "completed_task_ids" to listOf("task-01"),
    "changed_paths" to listOf("runtime-domain/model/X.kt"),
    "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
    "reconciliation_evidence" to linkedMapOf(
      "reconciled" to true,
      "evidence" to "Verified X.kt matches the plan commitment; no edit was required. ".repeat(80),
    ),
    "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
    "reconciled_state" to linkedMapOf("reconciled" to true),
    "deferred_repair_item_ids" to emptyList<String>(),
    "repair_item_results" to emptyList<Map<String, Any?>>(),
  )

  private fun withDefaultLocale(locale: Locale, block: () -> Unit) {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
      block()
    } finally {
      Locale.setDefault(previous)
    }
  }
}
