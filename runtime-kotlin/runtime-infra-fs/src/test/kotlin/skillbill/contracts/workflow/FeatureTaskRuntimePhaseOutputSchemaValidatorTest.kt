package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePhaseOutputSchemaValidatorTest {
  private val wellFormed =
    """
    contract_version: "0.1"
    phase_id: "plan"
    status: "completed"
    summary: "Produced an ordered implementation plan."
    produced_outputs:
      tasks: ["task-1", "task-2"]
    """.trimIndent()

  @Test
  fun `well-formed phase output passes validation`() {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(wellFormed, "plan")
  }

  @Test
  fun `empty object fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText("{}", "plan")
    }
  }

  @Test
  fun `output missing a required field fails validation`() {
    val missingSummary =
      """
      contract_version: "0.1"
      phase_id: "plan"
      status: "completed"
      produced_outputs:
        tasks: ["task-1"]
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(missingSummary, "plan")
    }
  }

  @Test
  fun `output with an unknown extra field fails validation`() {
    val extraField =
      """
      contract_version: "0.1"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs:
        tasks: ["task-1"]
      rogue_field: "nope"
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(extraField, "plan")
    }
  }

  @Test
  fun `output with the wrong contract version fails validation`() {
    val wrongVersion = wellFormed.replace("\"0.1\"", "\"9.9\"")
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(wrongVersion, "plan")
    }
  }

  @Test
  fun `output with an empty produced_outputs object fails validation`() {
    val emptyProducedOutputs =
      """
      contract_version: "0.1"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs: {}
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(emptyProducedOutputs, "plan")
    }
  }

  @Test
  fun `output with a non-empty produced_outputs object passes validation`() {
    val populated =
      """
      contract_version: "0.1"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs:
        tasks: ["task-1"]
        owner: "agent"
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(populated, "plan")
  }

  @Test
  fun `output with a non-empty derived_notes string passes validation`() {
    val withNotes = wellFormed + "\nderived_notes: \"a useful note\""
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(withNotes, "plan")
  }

  @Test
  fun `output with a null derived_notes fails validation`() {
    val nullNotes = wellFormed + "\nderived_notes: null"
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(nullNotes, "plan")
    }
  }

  @Test
  fun `output with an empty derived_notes string fails validation`() {
    val emptyNotes = wellFormed + "\nderived_notes: \"\""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(emptyNotes, "plan")
    }
  }

  @Test
  fun `output with an invalid status enum fails validation`() {
    val badStatus = wellFormed.replace("status: \"completed\"", "status: \"halfway\"")
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(badStatus, "plan")
    }
  }

  @Test
  fun `malformed yaml fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(
        "contract_version: \"0.1\"\n  : broken",
        "plan",
      )
    }
  }

  @Test
  fun `non-object root fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText("- just-a-list", "plan")
    }
  }
}
