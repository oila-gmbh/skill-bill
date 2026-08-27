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
    """{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
      """"produced_outputs":{"value":"Plan prose."}}"""

  @Test
  fun `valid JSON is accepted unchanged and is not rewritten`() {
    val result = adapter.validatePhaseOutput(validJson, "plan")

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    assertEquals(null, accepted.normalizedOutput.envelope["repair_evidence"])
    assertTrue(accepted.normalizedOutput.canonicalJson.contains("\"phase_id\":\"plan\""))
  }

  @Test
  fun `a reconciliation report placed beside produced_outputs is moved into it`() {
    // The shape observed on wftr-20260824-125937-qn99: an implement receipt whose work landed, then
    // 42KB of it discarded because reconciled_state sat next to produced_outputs instead of in it.
    val misplaced =
      """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
        """"summary":"Reconciled the repository to the intended state.",""" +
        """"produced_outputs":{"projection_kind":"implementation_receipt","changed_paths":["a/B.kt"],""" +
        """"reconciliation_evidence":{"reconciled":true,"evidence":"tree at target"}},""" +
        """"reconciled_state":{"reconciled":true}}"""

    val result = adapter.validatePhaseOutput(misplaced, "implement")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
      repaired.evidence.operation,
    )
    assertFalse(
      repaired.normalizedOutput.envelope.containsKey("reconciled_state"),
      "the stray root key is what the closed envelope rejects, so it must not survive at the root",
    )
    assertTrue(
      repaired.normalizedOutput.canonicalJson.contains("\"reconciled_state\""),
      "the report itself is the producer's evidence and must be kept, one level down",
    )
    @Suppress("UNCHECKED_CAST")
    val produced = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(mapOf("reconciled" to true), produced["reconciled_state"])
    assertEquals(
      mapOf("reconciled" to true, "evidence" to "tree at target"),
      produced["reconciliation_evidence"],
      "a member produced_outputs already carried is untouched",
    )
  }

  @Test
  fun `an absent summary is recovered from the prose the producer wrote before the envelope`() {
    // The shape observed on wftr-20260824-173259-uiwi: a 13-task implement receipt discarded because
    // the one descriptive field nothing branches on was narrated above the fence instead of inside it.
    val narrated = """
      |Formatting-risk check is clean: no added line exceeds 100 characters outside imports.
      |
      |All 13 plan tasks are converged; no build, test, or lint invocation was made in this phase.
      |
      |```json
      |{"contract_version":"0.4","phase_id":"implement","status":"completed",
      |"produced_outputs":{"projection_kind":"implementation_receipt","changed_paths":["a/B.kt"]}}
      |```
    """.trimMargin()

    val result = adapter.validatePhaseOutput(narrated, "implement")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      "All 13 plan tasks are converged; no build, test, or lint invocation was made in this phase.",
      repaired.normalizedOutput.envelope["summary"],
      "the paragraph nearest the envelope describes the state the envelope reports",
    )
    @Suppress("UNCHECKED_CAST")
    val produced = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(listOf("a/B.kt"), produced["changed_paths"], "the receipt itself must survive intact")
  }

  @Test
  fun `an absent summary with no prose to recover is marked rather than fabricated`() {
    val bare =
      """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
        """"produced_outputs":{"projection_kind":"implementation_receipt"}}"""

    val result = adapter.validatePhaseOutput(bare, "implement")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    val summary = repaired.normalizedOutput.envelope["summary"] as String
    assertTrue(
      summary.contains("reported no summary"),
      "with nothing of the producer's to recover, the fill must say so rather than invent a claim",
    )
  }

  @Test
  fun `a summary-less draft never competes with the complete envelope that follows it`() {
    // The fill would otherwise promote the draft to a second valid candidate and turn a response
    // the walker could already read into a multiple-candidates conflict.
    val draftThenReal = """
      |Discarded draft, missing its summary:
      |
      |```json
      |{"contract_version":"0.4","phase_id":"plan","status":"completed",
      |"produced_outputs":{"value":"Draft plan prose."}}
      |```
      |
      |Corrected final answer:
      |
      |```json
      |{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"Plan output.",
      |"produced_outputs":{"value":"Plan prose."}}
      |```
    """.trimMargin()

    val result = adapter.validatePhaseOutput(draftThenReal, "plan")

    val envelope = when (result) {
      is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair -> result.normalizedOutput.envelope
      is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged -> result.normalizedOutput.envelope
      else -> error("the complete envelope must decide the response, got $result")
    }
    assertEquals("Plan output.", envelope["summary"])
    @Suppress("UNCHECKED_CAST")
    val produced = envelope["produced_outputs"] as Map<String, Any?>
    assertEquals("Plan prose.", produced["value"], "the draft must not win")
  }

  @Test
  fun `a summary the producer did state is never replaced by surrounding prose`() {
    val narrated = """
      |Some narration that is not the summary.
      |
      |```json
      |{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"Plan output.",
      |"produced_outputs":{"value":"Plan prose."}}
      |```
    """.trimMargin()

    val result = adapter.validatePhaseOutput(narrated, "plan")

    val envelope = when (val outcome = adapter.validatePhaseOutput(narrated, "plan")) {
      is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair -> outcome.normalizedOutput.envelope
      is FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged -> outcome.normalizedOutput.envelope
      else -> error("an envelope in a fence must be accepted, got $result")
    }
    assertEquals("Plan output.", envelope["summary"])
  }

  @Test
  fun `a stray root key does not overwrite a member produced_outputs already states`() {
    val collision =
      """{"contract_version":"0.4","phase_id":"implement","status":"completed","summary":"Done.",""" +
        """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"stated"}},""" +
        """"reconciled_state":{"reconciled":false}}"""

    val result = adapter.validatePhaseOutput(collision, "implement")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)

    @Suppress("UNCHECKED_CAST")
    val produced = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(
      mapOf("reconciled" to true, "evidence" to "stated"),
      produced["reconciled_state"],
      "the value the producer placed deliberately wins over the stray copy",
    )
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
      """{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
        """"produced_outputs":{"value":"Plan prose.","notes":[{"id":"task-1"}]}}"""
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
    assertEquals("Plan prose.", producedOutputs["value"])
    assertEquals(listOf(mapOf("id" to "task-1")), producedOutputs["notes"])
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
      """{"contract_version":"0.4","phase_id":"plan","status":"completed",""" +
        """"summary":"literal } ] and escaped \"quote\"","produced_outputs":{"value":"Plan prose."}}"""

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
      """{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"Plan output.",""" +
        """"produced_outputs":{"value":"Plan prose A.","notes":["n-0"]},""" +
        """"produced_outputs":{"notes":["n-1"],"value":"Plan prose B."}}"""

    val result = adapter.validatePhaseOutput(payload, "plan")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)

    @Suppress("UNCHECKED_CAST")
    val producedOutputs = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals("Plan prose A.", producedOutputs["value"])
    assertEquals(listOf("n-0", "n-1"), producedOutputs["notes"])
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
    val malformed = """{"contract_version":"0.4","phase_id":"plan","status":"completed"}""".dropLast(1)

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
      "{\"contract_version\": \"0.4\", phase_id: \"plan\", status: \"completed\", " +
        "summary: \"brace } in a scalar\", produced_outputs: {value: \"Plan prose.\"}"
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
    val malformed = "{\"contract_version\":\"0.4\"" + "]".repeat(9)

    val result = adapter.validatePhaseOutput(malformed, "plan")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED, rejected.code)
  }

  @Test
  fun `malformed and ambiguous candidates return stable rejection codes`() {
    val malformed = adapter.validatePhaseOutput("{\"contract_version\": :}", "plan")
    val malformedRejection = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(malformed)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED, malformedRejection.code)

    val truncated = adapter.validatePhaseOutput("{\"contract_version\":\"0.4\",", "plan")
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
  fun `audit extra closer before trailing verdict is dropped and the envelope is kept`() {
    val payload =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"Audited production.",""" +
        """"produced_outputs":{"gaps":[]},"derived_notes":"no production gap"},"verdict":"satisfied"}"""

    val result = adapter.validatePhaseOutput(payload, "audit")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals("satisfied", repaired.normalizedOutput.envelope["verdict"])
    assertEquals("audit", repaired.normalizedOutput.envelope["phase_id"])
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      repaired.evidence.operation,
    )
  }

  @Test
  fun `audit nested verdict with a missing closer is closed then aligned to the expected shape`() {
    val malformed =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"Audited production.",""" +
        """"produced_outputs":{"gaps":[],"verdict":"satisfied"}"""

    val result = adapter.validatePhaseOutput(malformed, "audit")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals("satisfied", repaired.normalizedOutput.envelope["verdict"])
    assertEquals("audit", repaired.normalizedOutput.envelope["phase_id"])
  }

  @Test
  fun `audit nested verdict is hoisted onto the expected envelope shape`() {
    val nested =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"Audited production.",""" +
        """"produced_outputs":{"gaps":[],"verdict":"satisfied"}}"""

    val result = adapter.validatePhaseOutput(nested, "audit")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals("satisfied", repaired.normalizedOutput.envelope["verdict"])
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
      repaired.evidence.operation,
    )
    @Suppress("UNCHECKED_CAST")
    val produced = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>
    assertEquals(null, produced["verdict"])
  }

  @Test
  fun `a spaced Kotlin display-name construct symbol is salvaged to Type before schema`() {
    val spaced =
      "AgentRunServiceRuntimeComponentTest.runtime component exposes agent run service " +
        "with filesystem launcher binding"
    val envelope =
      """{"contract_version":"0.4","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
        """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"tree at target"},""" +
        """"repair_receipt":{"contract_version":"0.2","entries":[{"finding_id":"F-002","severity":"minor",""" +
        """"outcome":"addressed","constructs":[{"symbol":"$spaced",""" +
        """"file":"AgentRunServiceRuntimeComponentTest.kt"}],""" +
        """"intent":"Match junie spawnFailed facts after copilot removal"}]}}}"""

    val result = adapter.validatePhaseOutput(envelope, "implement_fix")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair>(result)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
      repaired.evidence.operation,
    )
    @Suppress("UNCHECKED_CAST")
    val produced = repaired.normalizedOutput.envelope["produced_outputs"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val receipt = produced["repair_receipt"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val entries = receipt["entries"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val constructs = entries.single()["constructs"] as List<Map<String, Any?>>
    assertEquals("AgentRunServiceRuntimeComponentTest", constructs.single()["symbol"])
  }

  @Test
  fun `unsupported block YAML is rejected without guessed structural edits`() {
    // SKILL-187 AC-005: block indentation is outside conservative flow repair; never invent closers.
    val blockYaml =
      """
        contract_version: "0.4"
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
