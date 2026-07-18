package skillbill.contracts.workflow

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
  fun `review output carrying a top-level verdict and findings passes validation`() {
    val reviewWithVerdict =
      """
      contract_version: "0.1"
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
      """{"contract_version":"0.1","phase_id":"plan","status":"completed",""" +
        """"summary":"ok","produced_outputs":{"tasks":["task-1"]}}"""
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(rawJson, "plan")
  }

  @Test
  fun `json inside a fenced json block passes validation`() {
    val fenced =
      """
      Here is the plan output.

      ```json
      {"contract_version":"0.1","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"tasks":["task-1"]}}
      ```

      Done.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(fenced, "plan")
  }

  @Test
  fun `markdown prefixed audit gaps normalize once and select the audit gap edge`() {
    val wrapped =
      """
      Audit evidence follows.
      ```json
      {
        "contract_version":"0.1",
        "phase_id":"audit",
        "status":"completed",
        "summary":"One criterion remains unmet.",
        "verdict":"gaps_found",
        "produced_outputs":{
          "unmet_criteria":[{"acceptance_criterion_ref":"AC-128","message":"Integration coverage is missing."}],
          "audit_repair_plan":{
            "contract_version":"0.1",
            "gaps":[{
              "gap_id":"ac-128-gap-1",
              "acceptance_criterion_ref":"AC-128",
              "acceptance_criterion_text":"Integration coverage exists.",
              "failure_evidence":"The integration suite has no scenario for the feature.",
              "diagnosis":"Add the missing integration scenario.",
              "affected_boundary":"runtime integration tests",
              "repair_items":[{
                "repair_item_id":"ac-128-gap-1-item-1",
                "intended_outcome":"The integration scenario verifies the feature.",
                "implementation_actions":["Add and execute the integration scenario."],
                "affected_paths_or_symbols":["runtime-core/src/test"],
                "required_verification":["Run the integration test."],
                "depends_on":[],
                "status":"pending"
              }]
            }]
          }
        }
      }
      ```
      The envelope above is authoritative.
      """.trimIndent()

    val normalized = FeatureTaskRuntimePhaseOutputValidatorAdapter().normalizePhaseOutput(wrapped, "audit")
    val produced = JsonSupport.anyToStringAnyMap(normalized.envelope["produced_outputs"]).orEmpty()
    val verdict = if ((produced["unmet_criteria"] as? List<*>).orEmpty().isNotEmpty()) {
      FeatureTaskRuntimeVerdict.GAPS_FOUND
    } else {
      FeatureTaskRuntimeVerdict.SATISFIED
    }
    val transition = assertIs<FeatureTaskRuntimeNextPhase.Next>(
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        verdict,
        edgeIterationCount = 0,
      ),
    )

    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT, transition.phaseId)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID, transition.loopId)
  }

  @Test
  fun `json with surrounding prose passes validation`() {
    val withProse =
      """
      I planned the work as follows:
      {"contract_version":"0.1","phase_id":"plan","status":"completed",
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
      {"contract_version":"0.1","phase_id":"plan","status":"completed","summary":"example",
       "produced_outputs":{"tasks":["example-task"]}}
      ```
      Here is the real output:
      ```json
      {"contract_version":"0.1","phase_id":"plan","status":"completed",
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
      {"contract_version":"0.1","phase_id":"audit","status":"completed","summary":"example",
       "verdict":"satisfied","produced_outputs":{"unmet_criteria":[]}}
      Here is the real output:
      {"contract_version":"0.1","phase_id":"audit","status":"completed",
       "summary":"every criterion met","verdict":"satisfied",
       "produced_outputs":{"unmet_criteria":[]}}
      """.trimIndent()
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(twoObjects, "audit")
    }
    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `object trailed by prose containing a stray brace still validates`() {
    // The naive first-`{`-to-last-`}` slice overshoots to the stray brace in the trailing prose and
    // parses as neither; the balanced-object scan isolates the genuine object.
    val withTrailingBrace =
      """
      {"contract_version":"0.1","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"tasks":["task-1"]}}
      Note: the template placeholder } above is intentional.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(withTrailingBrace, "plan")
  }

  @Test
  fun `a brace inside a string value does not split the object`() {
    val braceInString =
      """{"contract_version":"0.1","phase_id":"plan","status":"completed",""" +
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
}
