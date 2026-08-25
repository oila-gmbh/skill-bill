package skillbill.application

import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prose handoff never rejects an upstream producer's output for its shape or size: an oversized
 * projection is truncated under budget rather than blocking the phase, and legacy free-form records
 * advance without quarantine. A durable handoff-envelope rejection (corrupted runtime state, not a
 * producer's payload) is the remaining loud-fail class this suite covers.
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

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
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
  fun `a projection that overflows its budget is truncated and the run still completes`() {
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

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val planPrompt = requireNotNull(
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .firstOrNull { phaseIdFromPrompt(it) == "plan" },
    )
    assertContains(planPrompt, "HANDOFF_TRUNCATED")
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("completed", record.status)
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

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val auditPrompt = requireNotNull(
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .firstOrNull { phaseIdFromPrompt(it) == "audit" },
    )
    assertContains(auditPrompt, "src/test/kotlin/Test001.kt")
    assertContains(auditPrompt, "src/test/kotlin/Test%03d.kt".format(WIDE_RECEIPT_LIST_ENTRIES))
  }

  @Test
  fun `a legacy free-form upstream record advances without quarantine or producer regeneration`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    val legacyPlan =
      """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Legacy plan.",""" +
        """"produced_outputs":{"steps":["do the thing"],"narration":"free-form legacy body"}}"""
    harness.seedPhase(
      "plan",
      "completed",
      1,
      phaseAgent("plan"),
      legacyPlan,
    )
    harness.retainExactProducerEvidence("plan", legacyPlan)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertNull(
      harness.recorder.loadQuarantinedRecords(WORKFLOW_ID)?.firstOrNull { it.producingPhaseId == "plan" },
      "prose-shaped plan output must not enter quarantine for shape-only rejection",
    )
    assertTrue(
      harness.launchedPromptPhaseOrder().any { it == "implement" },
      "the consumer advances from producer prose without regenerating the producer",
    )
  }

  @Test
  fun `a legacy handoff-envelope launch-seam block stays durably blocked on resume`() {
    // AC-014: only the planning-projection launch-seam rejection is re-enterable. A durable
    // handoff-envelope rejection is corruption drift a producer re-run cannot repair, so it keeps its
    // first-occurrence durable block and is never silently re-entered.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), validJsonOutput("implement"))
    harness.seedBlockedPhase(
      "audit",
      1,
      phaseAgent("audit"),
      "Feature-task-runtime phase 'audit' rejected a durable handoff envelope at the launch seam: " +
        "stale briefing row.",
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "handoff envelope")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "audit" },
      "a non-record-rejection block is never re-entered",
    )
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
          "completed_task_ids": ["task-1"],
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

private fun RunnerHarness.retainExactProducerEvidence(phaseId: String, output: String) {
  val bytes = output.encodeToByteArray()
  recorder.retainProducerOutput(
    ProducerOutputEvidence(
      workflowId = WORKFLOW_ID,
      phaseId = phaseId,
      attempt = 1,
      agentId = phaseAgent(phaseId),
      model = "test-model",
      recordedAt = Instant.EPOCH,
      byteSize = bytes.size.toLong(),
      sha256 = RejectedOutputDiagnosticService.sha256(bytes),
      payload = bytes,
    ),
  )
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
