package skillbill.contracts.workflow

import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputStructuralRepairTest {
  private val adapter = FeatureTaskRuntimePhaseOutputValidatorAdapter()

  private val validJson =
    """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
      """"produced_outputs":{"tasks":["task-1"]}}"""

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
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validJson), repaired.evidence.repairedDigest)
  }

  @Test
  fun `one missing nested delimiter is inserted before the existing outer closer`() {
    val validNestedJson =
      """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
        """"produced_outputs":{"tasks":[{"id":"task-1"}]}}"""
    val malformed = validNestedJson.replace("[{\"id\":\"task-1\"}]}}", "[{\"id\":\"task-1\"}}}")

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validNestedJson), repaired.evidence.repairedDigest)
    @Suppress("UNCHECKED_CAST")
    val producedOutputs = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(listOf(mapOf("id" to "task-1")), producedOutputs["tasks"])
  }

  @Test
  fun `failed whole-response repair does not suppress a valid embedded envelope`() {
    val wrapped =
      "{discarded response prefix\n" +
        "```json\n" +
        validJson +
        "\n```"

    val result = adapter.validatePhaseOutput(wrapped, "plan")

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals("plan", accepted.normalizedOutput.envelope["phase_id"])
  }

  @Test
  fun `malformed embedded envelope is repaired with evidence`() {
    val malformed = validJson.dropLast(1)
    val wrapped = "The final answer is:\n```json\n$malformed\n```"

    val result = adapter.validatePhaseOutput(wrapped, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validJson), repaired.evidence.repairedDigest)
    assertTrue(repaired.evidence.sourceLocation.offset > 0)
  }

  @Test
  fun `extracted envelope does not discard an extra closing bracket`() {
    val malformed = "$validJson]"
    val wrapped = "The final answer is:\n$malformed"

    val result = adapter.validatePhaseOutput(wrapped, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validJson), repaired.evidence.repairedDigest)
  }

  @Test
  fun `structural characters inside JSON strings remain unchanged`() {
    val payload =
      """{"contract_version":"0.3","phase_id":"plan","status":"completed",""" +
        """"summary":"literal } ] and escaped \"quote\"","produced_outputs":{"tasks":["task-1"]}}"""

    val result = adapter.validatePhaseOutput(payload, "plan")

    val unchanged = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals("literal } ] and escaped \"quote\"", unchanged.normalizedOutput.envelope["summary"])
  }

  @Test
  fun `duplicate scalar keys keep the first value`() {
    val duplicate = validJson.replace("\"phase_id\":\"plan\",", "\"phase_id\":\"plan\",\"phase_id\":\"audit\",")

    val result = adapter.validatePhaseOutput(duplicate, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS, repaired.evidence.operation)
    assertEquals("plan", repaired.normalizedOutput.envelope["phase_id"])
    assertEquals(sha256(duplicate), repaired.evidence.originalDigest)
  }

  @Test
  fun `duplicate object keys merge contents and concatenate arrays`() {
    val payload =
      """{"contract_version":"0.3","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
        """"produced_outputs":{"tasks":["task-1"]},"produced_outputs":{"notes":["n-1"],"tasks":["task-2"]}}"""

    val result = adapter.validatePhaseOutput(payload, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)

    @Suppress("UNCHECKED_CAST")
    val producedOutputs = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(listOf("task-1", "task-2"), producedOutputs["tasks"])
    assertEquals(listOf("n-1"), producedOutputs["notes"])
  }

  @Test
  fun `prose mentioning Duplicate does not skip a valid fenced envelope`() {
    val response = """
      Duplicate broker-test imports were removed.

      ```json
      $validJson
      ```
    """.trimIndent()

    val result = adapter.validatePhaseOutput(response, "plan")

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals("plan", accepted.normalizedOutput.envelope["phase_id"])
  }

  @Test
  fun `repaired syntax that fails schema remains rejected`() {
    val malformed = """{"contract_version":"0.3","phase_id":"plan","status":"completed"}""".dropLast(1)

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, rejected.code)
    val evidence = requireNotNull(rejected.structuralRepairEvidence) {
      "schema rejection after delimiter repair must retain payload-free structural evidence"
    }
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.JSON, evidence.format)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      evidence.operation,
    )
    assertEquals(sha256(malformed), evidence.originalDigest)
    assertFalse(evidence.toString().contains(malformed.dropLast(5)))
  }

  @Test
  fun `conservative YAML flow repair preserves quoted scalar content`() {
    val malformed =
      "{\"contract_version\": \"0.3\", phase_id: \"plan\", status: \"completed\", " +
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
  fun `many unmatched closing delimiters stop candidate generation at the bounded limit`() {
    val malformed = "{\"contract_version\":\"0.3\"" + "]".repeat(9)

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED, rejected.code)
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

  @Test
  fun `backtick-quoted brace in prose outside a fenced envelope is commentary not a competing document`() {
    val response = """
      All three carried Blockers are reconciled; no bare `}` follows `parseContentIdentities`.

      ```json
      $validJson
      ```
    """.trimIndent()

    val result = adapter.validatePhaseOutput(response, "plan")

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals("plan", accepted.normalizedOutput.envelope["phase_id"])
  }

  @Test
  fun `bare closing delimiter in prose outside a complete envelope is removed and the envelope is kept`() {
    val cases = listOf(
      "before fence" to "trailing fragment of a truncated draft }\n```json\n$validJson\n```",
      "after unfenced" to "$validJson\nNote: the template placeholder } above is intentional.",
      "after fence" to "```json\n$validJson\n```\nNote: the template placeholder } above is intentional.",
    )

    cases.forEach { (label, response) ->
      val result = adapter.validatePhaseOutput(response, "plan")
      val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(
        result,
        "$label should keep the envelope after dropping the stray closer",
      )
      assertEquals(
        FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
        repaired.evidence.operation,
        label,
      )
      assertEquals("plan", repaired.normalizedOutput.envelope["phase_id"], label)
      assertEquals("completed", repaired.normalizedOutput.envelope["status"], label)
    }
  }

  @Test
  fun `multiple strictly parseable delimiter candidates are rejected as ambiguous repair`() {
    val first = validJson.replace("Plan output.", "first")
    val second = validJson.replace("Plan output.", "second")
    val decision = StructuralRepairCandidateEngine.evaluateCandidates(
      candidates = listOf(
        Candidate(first, FeatureTaskRuntimePhaseOutputFormat.JSON, 4),
        Candidate(second, FeatureTaskRuntimePhaseOutputFormat.JSON, 8),
      ),
      originalText = "malformed",
      sourceLabel = "plan",
      sourceOffset = 0,
      sourceText = "malformed",
    )

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(decision)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR, rejected.code)
  }

  @Test
  fun `audit nested-verdict missing delimiter repairs then remains schema-invalid`() {
    // SKILL-187 AC-001: delimiter repair is syntax-only; nested verdict still fails the phase schema.
    val malformed =
      """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"SKILL187-AUDIT-DELIM",""" +
        """"produced_outputs":{"gaps":[],"verdict":"satisfied"}"""

    val result = adapter.validatePhaseOutput(malformed, "audit")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, rejected.code)
    val evidence = requireNotNull(rejected.structuralRepairEvidence) {
      "schema rejection after delimiter repair must retain payload-free structural evidence"
    }
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      evidence.operation,
    )
    assertEquals(sha256(malformed), evidence.originalDigest)
    assertFalse(rejected.reason.contains("SKILL187-AUDIT-DELIM"))
  }

  @Test
  fun `unsupported block YAML is rejected without guessed structural edits`() {
    // SKILL-187 AC-005: block indentation is outside conservative flow repair; never invent closers.
    val blockYaml =
      """
        contract_version: "0.3"
        phase_id: "audit"
        status: "completed"
        summary: "SKILL187-UNSUPPORTED-YAML"
        verdict: "satisfied"
        produced_outputs:
          gaps: []
      """.trimIndent()

    val decision = StructuralRepairCandidateEngine.repairExactText(blockYaml, "audit")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(
      requireNotNull(decision) { "non-conservative YAML must produce an explicit repair rejection" },
    )
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR, rejected.code)
    assertTrue(rejected.reason.contains("conservative flow"))
    assertFalse(rejected.reason.contains("SKILL187-UNSUPPORTED-YAML"))
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
}
