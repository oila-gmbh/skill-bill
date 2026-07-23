package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The projection budgets are sized against recorded runtime phase outputs, and a projection that
 * still overflows must block the phase durably instead of aborting the driver mid-run.
 */
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
    // Item count is bounded by the model caps the budget sums, so the byte dimension is the one an
    // overflowing payload actually trips: far fewer entries than the item cap, each far longer.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "preplan") {
            preplanOutput(OVERSIZED_DIGEST_ITEMS, entry = OVERSIZED_ENTRY)
          } else {
            validJsonOutput(phaseId)
          },
        )
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

  @Test
  fun `an ordinary feature's implementation receipt reaches audit rather than overflowing its budget`() {
    // The receipt is the widest projection: six ordinary lists plus changed_paths, whose own cap is
    // four times theirs. Sizing its budget for a single-field projection rejected any MEDIUM/LARGE
    // feature's receipt at audit — a durable block on a correct, schema-valid producer output.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") wideImplementationReceipt() else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val auditPrompt = requireNotNull(
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .firstOrNull { phaseIdFromPrompt(it) == "audit" },
    )
    assertContains(auditPrompt, "src/main/kotlin/Changed001.kt")
    assertContains(auditPrompt, "src/main/kotlin/Changed$WIDE_RECEIPT_CHANGED_PATHS.kt")
  }

  @Test
  fun `a legacy free-form upstream record blocks the consumer durably instead of aborting the driver`() {
    // F-002: the planning-projection parse seam threw an error type no run-loop catch site handled, so
    // resuming a run whose durable plan output predates the bounded projection unwound past the
    // handler that had already persisted STATUS_RUNNING, leaving the row running with no
    // blockedReason and crashing identically on every later resume.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase(
      "plan",
      "completed",
      1,
      phaseAgent("plan"),
      """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Legacy plan.",""" +
        """"produced_outputs":{"steps":["do the thing"],"narration":"free-form legacy body"}}""",
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "bounded planning projection")
    assertContains(blocked.blockedReason, "plan#produced_outputs")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "the consumer must not launch against an unparseable upstream record",
    )

    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", record.status)
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
    assertTrue(requireNotNull(record.blockedReason).isNotBlank())
  }

  // A receipt sized like a real MEDIUM/LARGE feature: many changed paths plus populated test and
  // deviation lists, every list within its own cap.
  private fun wideImplementationReceipt(): String {
    fun quoted(values: List<String>) = values.joinToString(",") { "\"$it\"" }
    val changedPaths = (1..WIDE_RECEIPT_CHANGED_PATHS).map { "src/main/kotlin/Changed%03d.kt".format(it) }
    val tests = (1..WIDE_RECEIPT_LIST_ENTRIES).map { "src/test/kotlin/Test%03d.kt".format(it) }
    val executed = tests.joinToString(",") { """{"name":"$it","outcome":"passed"}""" }
    return """
      {
        "contract_version": "0.2",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Implementation receipt.",
        "produced_outputs": {
          "projection_kind": "implementation_receipt",
          "contract_version": "0.1",
          "completed_task_ids": ["task-01"],
          "changed_paths": [${quoted(changedPaths)}],
          "tests_added": [${quoted(tests)}],
          "tests_updated": [${quoted(tests)}],
          "tests_executed": [$executed],
          "unresolved_items": [],
          "reconciliation_evidence": {"reconciled": true, "evidence": "Tree at target state."},
          "repository_checkpoint": {"fingerprint": "fixture-checkpoint-1"},
          "reconciled_state": {"reconciled": true}
        }
      }
    """.trimIndent()
  }

  private fun preplanEnvelope(): String =
    """{"contract_version":"0.2","phase_id":"preplan","status":"completed","summary":"Digest.",""" +
      """"produced_outputs":${PlanningProjectionFixtures.PREPLAN_DIGEST}}"""

  // The digest carries the declared preplanning_digest projection shape. Size is driven by repeating
  // bounded `risks` entries rather than one giant string, because each projection field is itself
  // length-capped — the budget is what a whole digest may weigh, not what one field may.
  // Builds a digest carrying exactly [totalItems] length-capped entries, spread across the digest's
  // list fields so no single field is unrealistically deep.
  private fun preplanOutput(totalItems: Int, entry: String = RISK_ENTRY): String {
    val fields = DIGEST_LIST_FIELDS.mapIndexed { index, name ->
      val count = totalItems / DIGEST_LIST_FIELDS.size +
        if (index < totalItems % DIGEST_LIST_FIELDS.size) 1 else 0
      val entries = List(count) { "\"$entry\"" }.joinToString(",")
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
          "contract_version": "0.1",
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

// The widest digest the model itself admits: every list field filled to its own entry cap. The item
// budget sums those caps, so this is deliverable by construction — a schema-valid projection can never
// be rejected for item count, and only a genuinely oversized payload trips the byte dimension.
private val LARGEST_DELIVERABLE_DIGEST_ITEMS =
  DIGEST_LIST_FIELDS.size * FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT

// One bounded digest entry. Short enough that a digest filled to every list cap still fits the byte
// budget, which is what makes the item dimension provably unreachable for a schema-valid digest.
private val RISK_ENTRY = "d".repeat(200)

// Well under the item caps, but heavy enough that the digest exceeds its byte budget.
private val OVERSIZED_ENTRY = "d".repeat(1_000)
private const val OVERSIZED_DIGEST_ITEMS: Int = 240

// Comfortably past the 64-item cap that used to reject this receipt, and within every current cap.
private const val WIDE_RECEIPT_CHANGED_PATHS: Int = 120
private const val WIDE_RECEIPT_LIST_ENTRIES: Int = 40
