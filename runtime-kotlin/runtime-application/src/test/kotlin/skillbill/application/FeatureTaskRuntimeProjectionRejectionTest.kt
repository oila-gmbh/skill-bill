package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The projection budgets are sized against recorded runtime phase outputs, and a projection that
 * still overflows must block the phase durably instead of aborting the driver mid-run.
 */
// Largest `preplan` output across the 239 durable phase outputs the budgets were sized against.
private const val LARGEST_RECORDED_PREPLAN_BYTES = 131_901

class FeatureTaskRuntimeProjectionRejectionTest {
  @Test
  fun `a preplan digest at the largest recorded size is delivered to plan, not rejected`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "preplan") preplanOutput(LARGEST_RECORDED_PREPLAN_BYTES) else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val planPrompt = requireNotNull(
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .firstOrNull { phaseIdFromPrompt(it) == "plan" },
    )
    // Delivered whole, not truncated: the digest body reaches the consumer verbatim.
    assertContains(planPrompt, "d".repeat(LARGEST_RECORDED_PREPLAN_BYTES / 2))
  }

  @Test
  fun `a projection that overflows its budget blocks the phase durably instead of aborting the run`() {
    val oversized = FeatureTaskRuntimeHandoffProjectionBudget.PREPLAN_DIGEST_RECEIPT.maxUtf8Bytes + 8_192
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "preplan") preplanOutput(oversized) else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "handoff projection")
    assertContains(blocked.blockedReason, "budget")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "plan" })

    // The rejection is ledgered: a resume sees a blocked row with an operator-facing disposition
    // rather than a row left running by a driver that died mid-phase.
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", record.status)
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
    assertTrue(requireNotNull(record.blockedReason).isNotBlank())
  }

  private fun preplanOutput(bodyBytes: Int): String {
    val envelope = { body: String ->
      """
        {
          "contract_version": "0.2",
          "phase_id": "preplan",
          "status": "completed",
          "summary": "Preplanning digest.",
          "produced_outputs": {"preplanning_digest": {"notes": "$body"}}
        }
      """.trimIndent()
    }
    val overhead = envelope("").toByteArray(Charsets.UTF_8).size
    return envelope("d".repeat((bodyBytes - overhead).coerceAtLeast(1)))
  }
}
