package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputSchemaValidatorTest {
  private val wellFormed =
    """
    contract_version: "0.4"
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
  fun `adapter repair result is followed by the existing phase schema path`() {
    val malformed =
      """{"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"ok",""" +
        """"produced_outputs":{"tasks":["task-1"]}}]"""

    val normalized = FeatureTaskRuntimePhaseOutputValidatorAdapter().normalizePhaseOutput(malformed, "plan")

    assertContains(normalized.canonicalJson, "\"phase_id\":\"plan\"")
    assertEquals("plan", normalized.envelope["phase_id"])
  }

  @Test
  fun `blocked output accepts a typed non-retryable disposition`() {
    val blocked = wellFormed
      .replace("status: \"completed\"", "status: \"blocked\"") +
      "\nfailure_disposition: \"non_retryable_policy_conflict\""

    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(blocked, "plan")
  }

  @Test
  fun `unknown failure disposition fails validation`() {
    val blocked = wellFormed
      .replace("status: \"completed\"", "status: \"blocked\"") +
      "\nfailure_disposition: \"try_forever\""

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(blocked, "plan")
    }
  }

  @Test
  fun `audit output carrying the failing criteria alias fails validation at a pointer-anchored location`() {
    val alias =
      """
      contract_version: "0.4"
      phase_id: "audit"
      status: "completed"
      summary: "One criterion remains unmet."
      produced_outputs:
        failing_criteria:
          - acceptance_criterion_ref: "AC-001"
            message: "Integration coverage is missing."
      """.trimIndent()

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(alias, "audit")
    }
    assertContains(error.reason, "produced_outputs")
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
      contract_version: "0.4"
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
      contract_version: "0.4"
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
    val wrongVersion = wellFormed.replace("\"0.4\"", "\"9.9\"")
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(wrongVersion, "plan")
    }
  }

  @Test
  fun `output whose phase id does not match the executing phase fails validation`() {
    val wrongPhase = wellFormed.replace("phase_id: \"plan\"", "phase_id: \"implement\"")

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(wrongPhase, "plan")
    }
  }

  @Test
  fun `output with an empty produced_outputs object fails validation`() {
    val emptyProducedOutputs =
      """
      contract_version: "0.4"
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
      contract_version: "0.4"
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
  fun `review output carrying a top-level verdict and findings passes validation`() {
    val reviewWithVerdict =
      """
      contract_version: "0.4"
      phase_id: "review"
      status: "completed"
      summary: "Reviewed the change and requested fixes."
      verdict: "changes_requested"
      produced_outputs:
        findings:
          - severity: "blocker"
            message: "Foo.kt leaks a connection"
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(reviewWithVerdict, "review")
  }

  @Test
  fun `verify_findings disposition accepts boundary provenance and unavailable flag`() {
    val verifyFindings =
      """
      contract_version: "0.4"
      phase_id: "verify_findings"
      status: "completed"
      summary: "Verified findings against spec intent."
      verdict: "findings_verified"
      produced_outputs:
        finding_dispositions:
          - finding_id: "F-001"
            disposition: "verified"
            reason: "Matches spec intent AC-002."
            severity: "major"
            location: "FeatureTaskRuntimePhaseWorkflowDefinition.kt"
            message: "Missing verify_findings wiring"
            boundary_context_unavailable: false
            selected_boundary_headings:
              - heading_id: "runtime-kotlin/agent/history.md#abc"
                source_path: "runtime-kotlin/agent/history.md"
          - finding_id: "F-002"
            disposition: "verified"
            reason: "Intent-only path with no eligible boundary."
            severity: "minor"
            location: "Foo.kt"
            message: "Out-of-tree reference"
            boundary_context_unavailable: true
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(verifyFindings, "verify_findings")
  }

  @Test
  fun `verify_findings disposition rejects boundary selections when context is unavailable`() {
    val verifyFindings =
      """
      contract_version: "0.4"
      phase_id: "verify_findings"
      status: "completed"
      summary: "Verified findings against spec intent."
      verdict: "findings_verified"
      produced_outputs:
        finding_dispositions:
          - finding_id: "F-001"
            disposition: "verified"
            reason: "Intent-only path with no eligible boundary."
            severity: "minor"
            location: "Foo.kt"
            message: "Out-of-tree reference"
            boundary_context_unavailable: true
            selected_boundary_headings:
              - heading_id: "runtime-kotlin/agent/history.md#abc"
                source_path: "runtime-kotlin/agent/history.md"
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(verifyFindings, "verify_findings")
    }
  }

  @Test
  fun `output omitting the optional verdict still validates`() {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(wellFormed, "plan")
  }

  @Test
  fun `output with a null verdict fails validation`() {
    val nullVerdict = wellFormed + "\nverdict: null"
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(nullVerdict, "plan")
    }
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

  @Test
  fun `raw json object passes validation`() {
    val rawJson =
      """{"contract_version":"0.4","phase_id":"plan","status":"completed",""" +
        """"summary":"ok","produced_outputs":{"tasks":["task-1"]}}"""
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(rawJson, "plan")
  }

  @Test
  fun `json inside a fenced json block passes validation`() {
    val fenced =
      """
      Here is the plan output.

      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"tasks":["task-1"]}}
      ```

      Done.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(fenced, "plan")
  }
}

class FeatureTaskRuntimePhaseOutputSchemaValidatorEnvelopeTest {

  @Test
  fun `json with surrounding prose passes validation`() {
    val withProse =
      """
      I planned the work as follows:
      {"contract_version":"0.4","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"tasks":["task-1"]}}
      Let me know if you need anything else.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(withProse, "plan")
  }

  @Test
  fun `multiple conflicting fenced envelopes fail loudly`() {
    val twoBlocks =
      """
      For reference the shape is:
      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"example",
       "produced_outputs":{"tasks":["example-task"]}}
      ```
      Here is the real output:
      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed",
       "summary":"real","produced_outputs":{"tasks":["task-1"]}}
      ```
      """.trimIndent()
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(twoBlocks, "plan")
    }
    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `multiple conflicting prose wrapped envelopes fail loudly`() {
    val twoObjects =
      """
      For reference the shape is:
      {"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"example",
       "verdict":"satisfied","produced_outputs":{"gaps":[]}}
      Here is the real output:
      {"contract_version":"0.4","phase_id":"audit","status":"completed",
       "summary":"every criterion met","verdict":"satisfied","produced_outputs":{"gaps":[]}}
      """.trimIndent()
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(twoObjects, "audit")
    }
    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `audit requires one coherent compact gaps array`() {
    val invalidProducedOutputs = listOf(
      """"verdict":"satisfied","produced_outputs":{"evidence":"complete"}""",
      """"verdict":"satisfied","produced_outputs":{"gaps":"none"}""",
      """"verdict":"satisfied","produced_outputs":{"gaps":[{"criterion":"AC-001"}]}""",
      """"verdict":"gaps_found","produced_outputs":{"gaps":[]}""",
      """"verdict":"gaps_found","produced_outputs":{"gaps":"gap"}""",
      """"verdict":"gaps_found","produced_outputs":{"gaps":[]}""",
    )

    invalidProducedOutputs.forEach { suffix ->
      val envelope =
        """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"audit",$suffix}"""
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "audit")
      }
    }
  }

  @Test
  fun `object trailed by prose containing a stray brace still validates`() {
    // The naive first-`{`-to-last-`}` slice overshoots to the stray brace in the trailing prose and
    // parses as neither; the balanced-object scan isolates the genuine object.
    val withTrailingBrace =
      """
      {"contract_version":"0.4","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"tasks":["task-1"]}}
      Note: the template placeholder } above is intentional.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(withTrailingBrace, "plan")
  }

  @Test
  fun `a brace inside a string value does not split the object`() {
    val braceInString =
      """{"contract_version":"0.4","phase_id":"plan","status":"completed",""" +
        """"summary":"handles a literal } brace in a value","produced_outputs":{"tasks":["task-1"]}}"""
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(braceInString, "plan")
  }

  @Test
  fun `a top-level json array of criteria still fails validation`() {
    // A verifying phase that answers with a bare array carries no envelope object; no extraction can
    // salvage it, so the gate must still fail loudly (the retry directive is what corrects the agent).
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(
        """[{"criterion":"AC-1","met":true},{"criterion":"AC-2","met":false}]""",
        "audit",
      )
    }
  }

  @Test
  fun `a restated earlier envelope never outranks the final envelope`() {
    val staleThenReal =
      """
      Earlier draft of the audit result:
      ```json
      {"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"draft",
       "verdict":"satisfied","produced_outputs":{"gaps":[]}}
      ```
      Corrected final answer:
      ```json
      {"contract_version":"0.4","phase_id":"audit","status":"completed",
      "verdict":"gaps_found","produced_outputs":{"gaps":[{
        "criterion":"AC-128","note":"Integration behavior is missing."}]}}
      ```
      """.trimIndent()

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(staleThenReal, "audit")
    }

    assertContains(error.reason, "summary")
  }

  @Test
  fun `the final envelope wins over an earlier discarded draft`() {
    val draftThenReal =
      """
      Discarded draft, missing its summary:
      ```json
      {"contract_version":"0.4","phase_id":"audit","status":"completed",
       "verdict":"satisfied","produced_outputs":{"gaps":[]}}
      ```
      Corrected final answer:
      ```json
      {"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"one gap remains",
       "verdict":"gaps_found","produced_outputs":{"gaps":[{
         "criterion":"AC-128","note":"Rejected lanes are omitted from the aggregate."}]}}
      ```
      """.trimIndent()

    val normalized = FeatureTaskRuntimePhaseOutputValidatorAdapter().normalizePhaseOutput(draftThenReal, "audit")

    assertEquals("gaps_found", normalized.envelope["verdict"])
  }

  @Test
  fun `the same envelope restated with reordered keys is not a conflict`() {
    val reordered =
      """
      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"tasks":["task-1"],"notes":["note-1"]}}
      ```
      Restating the same envelope with the fields in a different order:
      ```json
      {"produced_outputs":{"notes":["note-1"],"tasks":["task-1"]},"summary":"ok",
       "status":"completed","phase_id":"plan","contract_version":"0.4"}
      ```
      """.trimIndent()

    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(reordered, "plan")
  }

  @Test
  fun `array element order still distinguishes conflicting envelopes`() {
    val reordered =
      """
      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"tasks":["task-1","task-2"]}}
      ```
      ```json
      {"contract_version":"0.4","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"tasks":["task-2","task-1"]}}
      ```
      """.trimIndent()

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(reordered, "plan")
    }

    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `prose with no json object still fails validation`() {
    val proseOnly =
      """
      ## Implementation Plan
      - **No existing injected clock seam**, so add one.
      - Wire the repository through the service.
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(proseOnly, "plan")
    }
  }

  @Test
  fun `audit nested verdict under produced_outputs fails with a payload-free root verdict constraint`() {
    // SKILL-187 AC-002: syntax is fine; the required top-level verdict is missing when nested only.
    val nested =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"SKILL187-NESTED",""" +
        """"produced_outputs":{"gaps":[],"verdict":"satisfied"}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(nested, "audit")
    }

    val payloadFree = requireNotNull(error.payloadFreeReason)
    assertTrue(payloadFree.contains("verdict"), "payload-free reason must name the root verdict field")
    assertFalse(payloadFree.contains("satisfied"), "payload-free reason must omit the nested value")
  }

  @Test
  fun `lenient audit normalization accepts gap entries the strict schema would reject`() {
    val body =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"lenient-gap",""" +
        """"verdict":"gaps_found","produced_outputs":{"gaps":[{"criterion":"AC-001",""" +
        """"note":"the behavior is absent","severity":"blocker"}]}}"""
    val lenient = FeatureTaskRuntimePhaseOutputSchemaValidator.normalizeAuditPhaseOutputLenient(body, "audit")

    assertEquals("audit", lenient.envelope["phase_id"])
    assertEquals("gaps_found", lenient.envelope["verdict"])
  }

  @Test
  fun `lenient verify_findings normalization accepts disposition fields the strict schema would reject`() {
    val longReason = "x".repeat(400)
    val body =
      """{"contract_version":"0.4","phase_id":"verify_findings","status":"completed",""" +
        """"summary":"lenient disposition","verdict":"findings_verified",""" +
        """"produced_outputs":{"finding_dispositions":[{"finding_id":"F-001",""" +
        """"disposition":"verified","reason":"$longReason","severity":"major",""" +
        """"location":"Example.kt","message":"Finding one","extra_field":"ignored"}]}}"""
    val lenient = FeatureTaskRuntimePhaseOutputSchemaValidator.normalizeVerifyingPhaseOutputLenient(
      body,
      "verify_findings",
    )

    assertEquals("verify_findings", lenient.envelope["phase_id"])
    assertEquals("findings_verified", lenient.envelope["verdict"])
  }

  @Test
  fun `completed implement_fix with a valid repair receipt validates`() {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(
      implementFixEnvelope(validRepairReceiptJson()),
      "implement_fix",
    )
  }

  @Test
  fun `completed implement_fix with a path-only construct is rejected`() {
    val pathOnly = validRepairReceiptJson().replace(
      """"symbol":"Type.member","file":"Type.kt"""",
      """"symbol":"runtime-kotlin/src/Type.kt"""",
    )
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(
        implementFixEnvelope(pathOnly),
        "implement_fix",
      )
    }
  }

  @Test
  fun `completed implement_fix omitting the repair receipt is rejected`() {
    val envelope =
      """{"contract_version":"0.4","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
        """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"tree at target"}}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "implement_fix")
    }
  }

  @Test
  fun `blocked implement_fix without a repair receipt still validates`() {
    val envelope =
      """{"contract_version":"0.4","phase_id":"implement_fix","status":"blocked","summary":"blocked",""" +
        """"failure_disposition":"needs_user_action","produced_outputs":{"reason":"operator pause"}}"""
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "implement_fix")
  }

  private fun implementFixEnvelope(receiptJson: String): String =
    """{"contract_version":"0.4","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
      """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"tree at target"},""" +
      """"repair_receipt":$receiptJson}}"""

  private fun validRepairReceiptJson(): String =
    """{"contract_version":"0.2","round_number":1,"pre_fix_checkpoint_sha":"${"a".repeat(40)}",""" +
      """"entries":[{"severity":"blocker","label":"Type","text":"unsafe mutation at the seam",""" +
      """"finding_id":"F-001","outcome":"addressed","constructs":[{"symbol":"Type.member","file":"Type.kt"}],""" +
      """"intent":"close the finding at Type.member"}]}"""

  @Test
  fun `completed build phase output validates nested build_receipt through production schema gate`() {
    val envelope = buildPhaseEnvelope(
      buildReceipt =
      """{"contract_version":"0.1","validation_status":"passed","checks":[],""" +
        """"repository_checkpoint":{"fingerprint":"fp"},"gate_run_count":1,""" +
        """"gate_runs":[{"duration_ms":1,"outcome":"passed","cache_mode":"cache_eligible",""" +
        """"executed_work_units":1}]}""",
    )
    FeatureTaskRuntimePhaseOutputValidatorAdapter().validatePhaseOutputText(envelope, "build")
  }

  @Test
  fun `completed build phase output with malformed build_receipt is rejected before acceptance`() {
    val envelope = buildPhaseEnvelope(
      buildReceipt =
      """{"contract_version":"0.1","validation_status":"passed","checks":[],""" +
        """"repository_checkpoint":{"fingerprint":"fp"}}""",
    )
    val result = FeatureTaskRuntimePhaseOutputValidatorAdapter().validatePhaseOutput(envelope, "build")
    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, rejected.code)
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputValidatorAdapter().validatePhaseOutputText(envelope, "build")
    }
  }

  private fun buildPhaseEnvelope(buildReceipt: String): String =
    """{"contract_version":"0.4","phase_id":"build","status":"completed",""" +
      """"summary":"Build satisfied by runtime-owned gate execution.",""" +
      """"verdict":"satisfied","produced_outputs":{"build_receipt":$buildReceipt}}"""
}
