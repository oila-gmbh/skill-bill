package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimeQuarantineSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeQuarantineSchemaValidatorTest {
  @Test
  fun `a well-formed quarantine record validates`() {
    FeatureTaskRuntimeQuarantineSchemaValidator.validate(validRecord(), "quarantine")
  }

  @Test
  fun `an unknown top-level key is rejected`() {
    val record = validRecord().toMutableMap().apply { put("unexpected", "x") }
    assertFailsWith<InvalidFeatureTaskRuntimeQuarantineSchemaError> {
      FeatureTaskRuntimeQuarantineSchemaValidator.validate(record, "quarantine")
    }
  }

  @Test
  fun `an unknown entry key is rejected`() {
    val record = recordWithEntry(validEntry().toMutableMap().apply { put("leaked_body", "secret") })
    assertFailsWith<InvalidFeatureTaskRuntimeQuarantineSchemaError> {
      FeatureTaskRuntimeQuarantineSchemaValidator.validate(record, "quarantine")
    }
  }

  @Test
  fun `a missing required entry field is rejected`() {
    val record = recordWithEntry(validEntry().toMutableMap().apply { remove("producing_phase_id") })
    assertFailsWith<InvalidFeatureTaskRuntimeQuarantineSchemaError> {
      FeatureTaskRuntimeQuarantineSchemaValidator.validate(record, "quarantine")
    }
  }

  @Test
  fun `a wrong-typed entry field is rejected`() {
    val record = recordWithEntry(validEntry().toMutableMap().apply { put("producing_iteration", "one") })
    assertFailsWith<InvalidFeatureTaskRuntimeQuarantineSchemaError> {
      FeatureTaskRuntimeQuarantineSchemaValidator.validate(record, "quarantine")
    }
  }

  @Test
  fun `an unknown rejection_class enum value is rejected`() {
    val record = recordWithEntry(validEntry().toMutableMap().apply { put("rejection_class", "made_up") })
    assertFailsWith<InvalidFeatureTaskRuntimeQuarantineSchemaError> {
      FeatureTaskRuntimeQuarantineSchemaValidator.validate(record, "quarantine")
    }
  }

  private fun validRecord(): Map<String, Any?> = mapOf(
    "contract_version" to "0.1",
    "entries" to listOf(validEntry()),
  )

  private fun recordWithEntry(entry: Map<String, Any?>): Map<String, Any?> = mapOf(
    "contract_version" to "0.1",
    "entries" to listOf(entry),
  )

  private fun validEntry(): Map<String, Any?> = mapOf(
    "producing_phase_id" to "plan",
    "consuming_phase_id" to "implement",
    "producing_iteration" to 1,
    "rejection_class" to "planning_projection_schema",
    "rejection_detail" to "plan#produced_outputs: projection_kind is missing",
    "regeneration_attempt" to 1,
    "quarantined_at_iteration" to 1,
    "rejected_record_payload" to "{\"legacy\":\"free-form\"}",
  )
}
