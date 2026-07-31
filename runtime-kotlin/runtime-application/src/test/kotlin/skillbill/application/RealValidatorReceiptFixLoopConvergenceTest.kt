@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-152 subtask 1 (AC-010): the three malformed-`implementation_receipt` classes observed in the field
 * all report at `$.reconciliation_evidence`, and all three previously spent the implement phase's whole fix
 * loop because the retry prompt was told only that something was rejected, never which constraint failed.
 * With the constraint text carried, each class must CONVERGE — the producer repairs the receipt and the run
 * completes strictly before the cap — rather than exhaust its attempts.
 *
 * The extra-key class converges more strongly than the other two: an unknown key inside a nested
 * fully-enumerated closed object is discarded during canonicalization, so it costs no fix-loop attempt at
 * all (AC-005). The other two are genuine structural faults that canonicalization must not repair, so they
 * cost exactly one attempt and are fixed from the constraint text.
 */
class RealValidatorReceiptFixLoopConvergenceTest {
  private val cap = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS

  @Test
  fun `an unknown key inside reconciliation_evidence is absorbed and consumes no fix-loop attempt`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") RECEIPT_WITH_EXTRA_EVIDENCE_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "an extra-key-only receipt must be canonicalized and advance on its first launch",
    )
  }

  @Test
  fun `a receipt missing reconciliation_evidence evidence converges before the cap`() {
    assertConvergesFromConstraintText(
      malformedReceipt = RECEIPT_MISSING_EVIDENCE,
      expectedConstraintFragments = arrayOf("required property 'evidence' not found"),
    )
  }

  @Test
  fun `a receipt with a string in place of reconciliation_evidence converges before the cap`() {
    assertConvergesFromConstraintText(
      malformedReceipt = RECEIPT_EVIDENCE_AS_STRING,
      expectedConstraintFragments = arrayOf("string found, object expected"),
    )
  }

  // The producer emits the malformed receipt once, then — as an agent reading an actionable rejection
  // would — emits a valid one. Convergence is the assertion: the run completes, implement launched exactly
  // twice, and that is strictly under the cap. A regression that drops the constraint text does not fail
  // here by leaking; it fails at the retry-prompt assertion, which is the signal this test exists for.
  private fun assertConvergesFromConstraintText(malformedReceipt: String, expectedConstraintFragments: Array<String>) {
    var implementAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId != "implement") {
          facts(validJsonOutput(phaseId))
        } else {
          implementAttempts += 1
          facts(if (implementAttempts == 1) malformedReceipt else validJsonOutput("implement"))
        }
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val implementLaunches = harness.launchedPromptPhaseOrder().count { it == "implement" }
    assertEquals(2, implementLaunches, "the receipt must be repaired on the first retry")
    assertTrue(
      implementLaunches < cap,
      "convergence means finishing under the cap, not exhausting it (cap=$cap, launches=$implementLaunches)",
    )

    val retryPrompt = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }[1]
    assertRetryPromptNamesConstraint(retryPrompt, "producer-projection", *expectedConstraintFragments)
  }
}

private fun receiptEnvelope(reconciliationEvidence: String): String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.1","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":$reconciliationEvidence,""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},""" +
    """"reconciled_state":{"reconciled":true},"deferred_repair_item_ids":[],"repair_item_results":[]}}"""

// Class 1: governed keys present and correct, plus one undeclared key inside the closed
// reconciliation_evidence object. The canonicalizer discards it before strict validation.
private val RECEIPT_WITH_EXTRA_EVIDENCE_KEY: String =
  receiptEnvelope("""{"reconciled":true,"evidence":"Fixture tree at target state.","confidence":"high"}""")

// Class 2: a required governed field omitted. Canonicalization never synthesizes it, so the schema rejects
// and the producer must add it.
private val RECEIPT_MISSING_EVIDENCE: String = receiptEnvelope("""{"reconciled":true}""")

// Class 3: a string where the closed object belongs. Canonicalization never coerces a type, so the schema
// rejects and the producer must emit the object.
private val RECEIPT_EVIDENCE_AS_STRING: String = receiptEnvelope(""""tree is at target state"""")
