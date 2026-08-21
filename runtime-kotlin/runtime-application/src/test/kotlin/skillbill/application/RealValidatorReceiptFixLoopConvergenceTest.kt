@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-152 subtask 1 (AC-010): the malformed-`implementation_receipt` classes observed in the field all
 * report at `$.reconciliation_evidence`, and what separates them is whether canonicalization may repair
 * them. The extra-key class is repairable — an unknown key inside a nested fully-enumerated closed object is
 * discarded during canonicalization, so it costs the phase nothing and advances on its first launch
 * (AC-005). The other three are genuine structural faults that canonicalization must never paper over, so
 * each one rejects, and the constraint that failed is recorded where a repair would be read from.
 *
 * The output-gate budget is one attempt, so a rejection settles the run at implement. Each case pins which
 * side of the canonicalization line its fixture falls on, which is the distinction that regressed in the
 * field.
 */
class RealValidatorReceiptFixLoopConvergenceTest {
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
  fun `a receipt missing reconciliation_evidence evidence is rejected, never synthesized`() {
    assertRejectedWithConstraintText(
      malformedReceipt = RECEIPT_MISSING_EVIDENCE,
      expectedConstraintFragments = arrayOf("required property 'evidence' not found"),
    )
  }

  @Test
  fun `a receipt with a string in place of reconciliation_evidence is rejected, never coerced`() {
    assertRejectedWithConstraintText(
      malformedReceipt = RECEIPT_EVIDENCE_AS_STRING,
      expectedConstraintFragments = arrayOf("string found, object expected"),
    )
  }

  /**
   * SKILL-169: the fourth field-observed class, and the one that actually reached a user. An over-length
   * `evidence` differs from the three above in that the producer's content is not wrong — only its size —
   * which is exactly the case canonicalization must not silently truncate to make the receipt fit.
   */
  @Test
  fun `an over-length reconciliation evidence is rejected, never truncated to fit`() {
    assertRejectedWithConstraintText(
      malformedReceipt = RECEIPT_EVIDENCE_TOO_LONG,
      expectedConstraintFragments = arrayOf("must be at most 4,096 characters long"),
    )
  }

  // A structural fault canonicalization must not repair: the gate rejects it, the run settles at implement
  // without reaching audit, and the constraint that failed is recorded on the private diagnostic. A
  // regression that quietly canonicalizes one of these fixtures fails here by completing the run.
  private fun assertRejectedWithConstraintText(malformedReceipt: String, expectedConstraintFragments: Array<String>) {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") malformedReceipt else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "a one-attempt budget leaves no relaunch",
    )
    assertTrue(
      !harness.launchedPromptPhaseOrder().contains("audit"),
      "a rejected receipt must not reach its consumer",
    )
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "implement" }.metadata.reason,
      *expectedConstraintFragments,
    )
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

// Class 4: every governed key present and well-typed, but `evidence` past its 4096-char cap — the shape a
// no-op reconciliation segment produces when it proves convergence path by path instead of reporting it.
// Built from a repeated clause so the fixture reads as the verification prose it stands in for.
private val RECEIPT_EVIDENCE_TOO_LONG: String = receiptEnvelope(
  "{\"reconciled\":true,\"evidence\":\"" +
    "Verified src/Foo.kt matches the plan commitment at checkpoint 8ffee05e; no edit was required. "
      .repeat(50) +
    "\"}",
)
