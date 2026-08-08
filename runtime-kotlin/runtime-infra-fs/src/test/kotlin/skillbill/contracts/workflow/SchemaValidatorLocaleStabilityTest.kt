package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * networknt renders violation messages through `MessageFormat` under the JVM default locale, and the
 * runtime echoes those messages verbatim into the retry prompt a producing agent must act on. A host in
 * a non-US region therefore used to change what the agent was told: `en_DE` regrouped the 4096-character
 * cap as "4.096" — which reads as a four-character budget — and `de_DE` translated the sentence outright,
 * leaving no machine-actionable constraint at all. This was not hypothetical: it broke CI on a macOS
 * runner whose region is DE while its language is English.
 *
 * Locking the rendering here rather than in the failing consumer keeps the guarantee where the cause is,
 * and the location assertion pins the path dialect that supplying a validator config would otherwise
 * silently switch (LEGACY -> JSON_POINTER), which callers parse back into dotted field paths.
 */
class SchemaValidatorLocaleStabilityTest {
  @Test
  fun `a length violation renders in English with US grouping under any host locale`() {
    withDefaultLocale(Locale.GERMANY) {
      val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
        FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
          payload = overLongEvidenceReceipt(),
          sourceLabel = "implement#produced_outputs",
        )
      }
      assertContains(error.message.orEmpty(), "must be at most 4,096 characters long")
    }
  }

  @Test
  fun `violation locations stay in the dotted dialect callers parse under any host locale`() {
    withDefaultLocale(Locale.GERMANY) {
      val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
        FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(
          payload = overLongEvidenceReceipt(),
          sourceLabel = "implement#produced_outputs",
        )
      }
      assertContains(error.message.orEmpty(), "\$.reconciliation_evidence.evidence")
    }
  }

  private fun overLongEvidenceReceipt(): Map<String, Any?> = linkedMapOf(
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
