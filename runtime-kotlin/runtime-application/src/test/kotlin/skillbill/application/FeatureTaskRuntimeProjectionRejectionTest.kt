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
// The bounded digest's real ceiling is the projection item budget, not the legacy byte figure: the
// pre-projection preplan receipt peaked at 131,901 UTF-8 bytes, but a digest is now a bounded set of
// length-capped entries, so the largest deliverable digest is one that exactly fills the item budget.
// One item of the budget is spent on the single-valued `rollout` field; the rest are list entries.
private val LARGEST_DELIVERABLE_DIGEST_ITEMS =
  FeatureTaskRuntimeHandoffProjectionBudget.PREPLAN_DIGEST_RECEIPT.maxCollectionItems - 1

class FeatureTaskRuntimeProjectionRejectionTest {
  @Test
  fun `a preplan digest at the largest deliverable size is delivered to plan, not rejected`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "preplan") preplanOutput(LARGEST_DELIVERABLE_DIGEST_ITEMS) else validJsonOutput(phaseId),
        )
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
    // Delivered whole, not truncated: every bounded digest entry reaches the consumer verbatim.
    assertContains(planPrompt, RISK_ENTRY)
    assertEquals(
      LARGEST_DELIVERABLE_DIGEST_ITEMS,
      planPrompt.split(RISK_ENTRY).size - 1,
      "every digest entry must be delivered, none dropped by truncation",
    )
  }

  @Test
  fun `a projection that overflows its budget blocks the phase durably instead of aborting the run`() {
    val oversized = LARGEST_DELIVERABLE_DIGEST_ITEMS + 8
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

  // The digest carries the declared preplanning_digest projection shape. Size is driven by repeating
  // bounded `risks` entries rather than one giant string, because each projection field is itself
  // length-capped — the budget is what a whole digest may weigh, not what one field may.
  // Builds a digest carrying exactly [totalItems] length-capped entries, spread across the digest's
  // list fields so no single field is unrealistically deep.
  private fun preplanOutput(totalItems: Int): String {
    val fields = DIGEST_LIST_FIELDS.mapIndexed { index, name ->
      val count = totalItems / DIGEST_LIST_FIELDS.size +
        if (index < totalItems % DIGEST_LIST_FIELDS.size) 1 else 0
      val entries = List(count) { "\"$RISK_ENTRY\"" }.joinToString(",")
      "\"$name\": [$entries]"
    }.joinToString(",\n          ")
    return """
      {
        "contract_version": "0.2",
        "phase_id": "preplan",
        "status": "completed",
        "summary": "Preplanning digest.",
        "produced_outputs": {
          "projection_kind": "preplanning_digest",
          $fields,
          "rollout": {"flag_required": false, "flag_pattern": "none", "notes": "No flag needed."}
        }
      }
    """.trimIndent()
  }
}

private val DIGEST_LIST_FIELDS = listOf(
  "affected_boundaries",
  "risks",
  "validation_strategy",
  "patterns_and_decisions",
  "unresolved_questions",
  "evidence_refs",
)

// One bounded digest entry, just under the projection's per-entry length cap.
private val RISK_ENTRY = "d".repeat(1_000)
