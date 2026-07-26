package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeHandoffFoundationSchemaValidatorTest {
  @Test
  fun `phase handoff validator rejects the legacy flat source shape`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseHandoffSchemaError> {
      FeatureTaskRuntimePhaseHandoffSchemaValidator.validate(
        mapOf("contract_version" to "0.2", "source_ref" to "upstream_phase_output:plan"),
        "implement.plan_receipt",
      )
    }
  }

  @Test
  fun `persistence validator rejects consumer delivery count posing as producer iteration`() {
    assertFailsWith<InvalidFeatureTaskRuntimePersistenceSchemaError> {
      FeatureTaskRuntimePersistenceSchemaValidator.validate(
        mapOf(
          "contract_version" to "0.2",
          "record_kind" to "delivered_projection",
          "workflow_id" to "wftr-1",
          "consumer_phase_id" to "audit",
          "producer_iteration" to 4,
          "repository_checkpoint" to mapOf("fingerprint" to "checkpoint"),
          "handoff_envelope" to emptyMap<String, Any?>(),
        ),
        "audit.delivery",
      )
    }
  }
}
