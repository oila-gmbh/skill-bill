package skillbill.application

import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import java.time.Instant
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
  fun `preplan prose value is delivered to plan`() {
    val prose = "Dense fixture preplan prose for plan."
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "preplan") preplanEnvelope(prose) else validJsonOutput(phaseId),
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
    assertContains(planPrompt, prose)
  }

  @Test
  fun `a projection that overflows its budget blocks the phase durably instead of aborting the run`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "plan") {
            planOutput(OVERSIZED_PLAN_ITEMS, entry = OVERSIZED_ENTRY)
          } else {
            validJsonOutput(phaseId)
          },
        )
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "handoff projection")
    assertContains(blocked.blockedReason, "budget")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "implement" })

    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
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

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val auditPrompt = requireNotNull(
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .firstOrNull { phaseIdFromPrompt(it) == "audit" },
    )
    assertContains(auditPrompt, "src/main/kotlin/Changed001.kt")
    assertContains(auditPrompt, "src/main/kotlin/Changed$WIDE_RECEIPT_CHANGED_PATHS.kt")
  }

  @Test
  fun `a legacy free-form upstream record quarantines and regenerates the producer instead of blocking`() {
    // SKILL-140 (AC-001, AC-002): a durable plan record predating the bounded projection (no
    // projection_kind) is no longer a first-occurrence durable block. The launch seam quarantines it as
    // private evidence and re-enters the plan phase; a subsequently valid plan advances the run with no
    // operator action. Out-of-band row surgery is the corruption fallback, not the primary recovery.
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
    // The plan phase regenerated, and the consumer advanced past it under the valid regenerated record.
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "plan" }, "the plan phase must regenerate")
    assertTrue(
      harness.launchedPromptPhaseOrder().any { it == "implement" },
      "the consumer advances after the producer regenerates a valid record",
    )
    // The rejected record survives as private quarantine evidence attributed to its producing phase.
    val quarantined = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    val entry = requireNotNull(quarantined.firstOrNull { it.producingPhaseId == "plan" })
    assertEquals("implement", entry.consumingPhaseId)
    assertTrue(entry.diagnosticIdentity?.startsWith("rod_") == true)
    assertEquals(false, entry.diagnosticDegraded)
    assertTrue("diagnostic_degraded" !in entry.toArtifactMap())
    assertTrue(Regex("[0-9a-f]{64}").matches(entry.rejectedRecordSha256))
    // Evidence is never delivered to any agent prompt.
    assertTrue(
      harness.launcher.requests.none {
        requireNotNull(it.skillRunRequest.promptOverride).contains("free-form legacy body")
      },
      "quarantined evidence must never reach an agent prompt",
    )
  }

  @Test
  fun `a legacy launch-seam projection-rejection block is re-entered and self-heals on resume`() {
    // SKILL-140 (AC-003, AC-008, AC-010): a consumer phase durably blocked by a PRE-quarantine build's
    // launch-seam planning-projection rejection (persisted needs_user_action) is stale, not terminal. On
    // resume the phase re-enters, the live seam quarantines the offending upstream record and regenerates
    // its producer, and the run advances with no reset and no operator surgery.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    // The upstream implement record predates the bounded projection (free-form body, no projection_kind).
    val legacyImplementation =
      """{"contract_version":"0.2","phase_id":"implement","status":"completed","summary":"Legacy impl.",""" +
        """"produced_outputs":{"steps":["did the thing"],"narration":"free-form legacy body"}}"""
    harness.seedPhase(
      "implement",
      "completed",
      1,
      phaseAgent("implement"),
      legacyImplementation,
    )
    harness.retainExactProducerEvidence("implement", legacyImplementation)
    // The consumer was blocked by the pre-quarantine build with the exact legacy launch-seam reason, and
    // those rejections already spent its fix-loop budget (attempt 4 > cap 3). Re-entry must restart the
    // budget — the consumer never actually ran — so it reaches the live seam instead of re-blocking.
    harness.seedBlockedPhase(
      "audit",
      4,
      phaseAgent("audit"),
      "Feature-task-runtime phase 'audit' rejected an upstream bounded planning projection at the launch " +
        "seam (workflow '$WORKFLOW_ID'): implement#produced_outputs fails schema validation.",
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // audit re-entered rather than re-surfacing the durable block, and the rejected producer regenerated.
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "audit" }, "the blocked audit phase must re-enter")
    assertTrue(
      harness.launchedPromptPhaseOrder().any { it == "implement" },
      "the rejected upstream producer regenerates",
    )
    // The rejected implement record survives as private quarantine evidence attributed to its producer.
    val quarantined = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    val entry = requireNotNull(quarantined.firstOrNull { it.producingPhaseId == "implement" })
    assertEquals("audit", entry.consumingPhaseId)
    assertTrue(entry.diagnosticIdentity?.startsWith("rod_") == true)
    assertEquals(false, entry.diagnosticDegraded)
    assertTrue("diagnostic_degraded" !in entry.toArtifactMap())
    assertTrue(Regex("[0-9a-f]{64}").matches(entry.rejectedRecordSha256))
    assertTrue(
      harness.launcher.requests.none {
        requireNotNull(it.skillRunRequest.promptOverride).contains("free-form legacy body")
      },
      "quarantined evidence must never reach an agent prompt",
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

  @Test
  fun `a launch-seam block already overwritten with fix-loop exhaustion is recovered from the ledger`() {
    // The production-observed state: a first re-entry of a legacy launch-seam block predated the budget
    // restart, spent the already-exhausted fix-loop budget, and overwrote the reason with the generic
    // exhaustion text. The ledger still shows the prior block was the launch-seam rejection, so the phase
    // re-enters, restarts its budget, reaches the live seam, quarantines the record, and self-heals.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    val legacyImplementation =
      """{"contract_version":"0.2","phase_id":"implement","status":"completed","summary":"Legacy impl.",""" +
        """"produced_outputs":{"steps":["did the thing"],"narration":"free-form legacy body"}}"""
    harness.seedPhase(
      "implement",
      "completed",
      1,
      phaseAgent("implement"),
      legacyImplementation,
    )
    harness.retainExactProducerEvidence("implement", legacyImplementation)
    val launchSeamReason =
      "Feature-task-runtime phase 'audit' rejected an upstream bounded planning projection at the launch " +
        "seam (workflow '$WORKFLOW_ID'): implement#produced_outputs fails schema validation."
    val exhaustionReason =
      "Phase 'audit' exhausted the bounded fix loop after 3 attempts (cap=3); the run blocks rather than " +
        "advancing on invalid output."
    // The persisted phase record carries the exhaustion overwrite; the ledger still shows the original
    // launch-seam rejection (older) followed by that exhaustion overwrite (newest).
    harness.seedBlockedPhase(
      "audit",
      4,
      phaseAgent("audit"),
      exhaustionReason,
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )
    listOf(3 to launchSeamReason, 4 to exhaustionReason).forEach { (attempt, reason) ->
      harness.recorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          workflowId = WORKFLOW_ID,
          action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          phaseId = "audit",
          attemptCount = attempt,
          resolvedAgentId = phaseAgent("audit"),
          blockedReason = reason,
        ),
      )
    }

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "audit" }, "the blocked audit phase must re-enter")
    val quarantined = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertTrue(
      quarantined.any { it.producingPhaseId == "implement" && it.consumingPhaseId == "audit" },
      "the rejected implement record is quarantined once the phase reaches the live seam",
    )
  }

  @Test
  fun `a launch-seam record rejection with no retained producer-output row blocks as absent evidence`() {
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
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "no retained evidence exists for attempt")
    assertTrue("unavailable" !in blocked.blockedReason)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "the consumer phase never launched",
    )
  }

  @Test
  fun `a launch-seam record rejection whose retained evidence read throws conflict blocks as store-refused`() {
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
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)
    harness.retainExactProducerEvidence("plan", legacyPlan)
    harness.io.database.producerOutputReadError =
      skillbill.ports.persistence.model.RejectedOutputDiagnosticError.Conflict("plan-evidence")

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "diagnostic store refused it (conflict)")
    assertTrue("free-form legacy body" !in blocked.blockedReason)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "the consumer phase never launched",
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

  private fun preplanEnvelope(value: String = "Fixture preplan prose."): String =
    """{"contract_version":"0.2","phase_id":"preplan","status":"completed","summary":"Prose.",""" +
      """"produced_outputs":{"value":"$value"}}"""

  private fun planOutput(totalItems: Int, entry: String): String {
    val tasks = (1..totalItems).joinToString(",") { index ->
      """{"task_id":"task-$index","description":"$entry","criterion_refs":["AC-001"],"test_obligations":["parity"]}"""
    }
    return """
      {
        "contract_version": "0.2",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Executable plan.",
        "produced_outputs": {
          "projection_kind": "executable_plan",
          "contract_version": "0.1",
          "mode": "direct",
          "tasks": [$tasks],
          "validation_strategy": ["focused gradle"]
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

private const val OVERSIZED_PLAN_ITEMS: Int = 64
private val OVERSIZED_ENTRY = "d".repeat(3_500)

// Comfortably past the 64-item cap that used to reject this receipt, and within every current cap.
private const val WIDE_RECEIPT_CHANGED_PATHS: Int = 120
private const val WIDE_RECEIPT_LIST_ENTRIES: Int = 40
