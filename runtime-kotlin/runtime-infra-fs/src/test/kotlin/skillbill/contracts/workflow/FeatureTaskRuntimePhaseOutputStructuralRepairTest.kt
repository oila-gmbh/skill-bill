package skillbill.contracts.workflow

import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.security.MessageDigest

class FeatureTaskRuntimePhaseOutputStructuralRepairTest {
  private val adapter = FeatureTaskRuntimePhaseOutputValidatorAdapter()

  private val validJson =
    """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{"tasks":["task-1"]}}"""

  @Test
  fun `valid JSON is accepted unchanged and is not rewritten`() {
    val result = adapter.validatePhaseOutput(validJson, "plan")

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals(null, accepted.normalizedOutput.envelope["repair_evidence"])
    assertTrue(accepted.normalizedOutput.canonicalJson.contains("\"phase_id\":\"plan\""))
  }

  @Test
  fun `observed extra closing delimiter is removed outside strings`() {
    val malformed = "$validJson]"
    val result = adapter.validatePhaseOutput(malformed, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.JSON, repaired.evidence.format)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validJson), repaired.evidence.repairedDigest)
    assertEquals("plan", repaired.normalizedOutput.envelope["phase_id"])
    assertTrue(repaired.evidence.sourceLocation.offset == validJson.length)
  }

  @Test
  fun `one missing closing delimiter is added and reparsed`() {
    val malformed = validJson.dropLast(1)
    val result = adapter.validatePhaseOutput(malformed, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, repaired.evidence.operation)
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validJson), repaired.evidence.repairedDigest)
  }

  @Test
  fun `one missing nested delimiter is inserted before the existing outer closer`() {
    val validNestedJson =
      """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{"tasks":[{"id":"task-1"}]}}"""
    val malformed = validNestedJson.replace("[{\"id\":\"task-1\"}]}}", "[{\"id\":\"task-1\"}}}")

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, repaired.evidence.operation)
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validNestedJson), repaired.evidence.repairedDigest)
    @Suppress("UNCHECKED_CAST")
    val producedOutputs = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(listOf(mapOf("id" to "task-1")), producedOutputs["tasks"])
  }

  @Test
  fun `structural characters inside JSON strings remain unchanged`() {
    val payload =
      """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"literal } ] and escaped \"quote\"","produced_outputs":{"tasks":["task-1"]}}"""

    val result = adapter.validatePhaseOutput(payload, "plan")

    val unchanged = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals("literal } ] and escaped \"quote\"", unchanged.normalizedOutput.envelope["summary"])
  }

  @Test
  fun `duplicate keys are rejected before structural repair`() {
    val duplicate = validJson.replace("\"phase_id\":\"plan\",", "\"phase_id\":\"plan\",\"phase_id\":\"plan\",")

    val result = adapter.validatePhaseOutput(duplicate, "plan")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY, rejected.code)
    assertFalse(rejected.reason.contains("phase_id\":\"plan\""))
  }

  @Test
  fun `repaired syntax that fails schema remains rejected`() {
    val malformed = """{"contract_version":"0.3","phase_id":"plan","status":"completed"""

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, rejected.code)
  }

  @Test
  fun `conservative YAML flow repair preserves quoted scalar content`() {
    val malformed =
      "{contract_version: \"0.3\", phase_id: \"plan\", status: \"completed\", " +
        "summary: \"brace } in a scalar\", produced_outputs: {tasks: [\"task-1\"]}"
    val repairedText = "$malformed}"

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.YAML, repaired.evidence.format)
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(repairedText), repaired.evidence.repairedDigest)
    assertEquals("brace } in a scalar", repaired.normalizedOutput.envelope["summary"])
  }

  @Test
  fun `malformed and ambiguous candidates return stable rejection codes`() {
    val malformed = adapter.validatePhaseOutput("{\"contract_version\": :}", "plan")
    val malformedRejection = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(malformed)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED, malformedRejection.code)

    val truncated = adapter.validatePhaseOutput("{\"contract_version\":\"0.3\",", "plan")
    val truncatedRejection = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(truncated)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE, truncatedRejection.code)

    val first = validJson.replace("Plan output.", "first")
    val second = validJson.replace("Plan output.", "second")
    val ambiguity = adapter.validatePhaseOutput("$first\n$second", "plan")
    val ambiguityRejection = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(ambiguity)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES, ambiguityRejection.code)
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
}
