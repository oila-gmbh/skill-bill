package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * F-001: the durable decoder must loud-fail undeclared and unsupported records so an append cannot
 * load them and rewrite the store without that evidence.
 */
class FeatureTaskRuntimeQuarantineModelsTest {
  @Test
  fun `an unsupported field loud-fails rather than being dropped on decode`() {
    val valid = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val envelopeError = assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeQuarantineEntriesFromWire(valid + ("unexpected" to true))
    }
    val leaked = listOf(identityEntry().toArtifactMap() + ("leaked_body" to "secret"))
    val entryError = assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeQuarantineEntriesFromWire(valid + ("entries" to leaked))
    }
    listOf(envelopeError, entryError).forEach { error ->
      assertFalse(
        error.message.orEmpty().contains("secret"),
        "decode errors must not carry undeclared field values",
      )
    }
    assertContains(envelopeError.message.orEmpty(), "unexpected")
    assertContains(entryError.message.orEmpty(), "leaked_body")
  }

  @Test
  fun `an unsupported contract version loud-fails so the store is not rewritten`() {
    val valid = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeQuarantineEntriesFromWire(valid + ("contract_version" to "0.2"))
    }
    assertContains(error.message.orEmpty(), "unsupported contract version")
    assertContains(error.message.orEmpty(), FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE)
  }

  @Test
  fun `diagnostic_degraded false is rejected rather than loaded as an unmarked identity entry`() {
    val wire = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val markedFalse = listOf(identityEntry().toArtifactMap() + ("diagnostic_degraded" to false))
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeQuarantineEntriesFromWire(wire + ("entries" to markedFalse))
    }
  }

  private fun identityEntry() = FeatureTaskRuntimeQuarantineEntry(
    producingPhaseId = "plan",
    consumingPhaseId = "implement",
    producingIteration = 1,
    rejectionClass = QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
    rejectionDetail = "plan#produced_outputs: projection_kind is missing",
    regenerationAttempt = 1,
    quarantinedAtIteration = 1,
    diagnosticIdentity = "rod_prechange",
    rejectedRecordByteSize = 11,
    rejectedRecordSha256 = "a".repeat(64),
  )
}
