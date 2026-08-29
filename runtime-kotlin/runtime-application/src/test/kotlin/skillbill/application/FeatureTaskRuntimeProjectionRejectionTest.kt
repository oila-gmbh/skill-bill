package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Implement feeds audit as phase prose. Missing value blocks the consumer durably;
 * quarantine/regenerate for implement is gone. Projection byte ceilings were dropped.
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
  fun `oversized implement prose reaches audit without a projection budget block`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "implement") {
            oversizeImplementProse()
          } else {
            validJsonOutput(phaseId)
          },
        )
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `an ordinary feature's implement prose reaches audit rather than overflowing its budget`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") wideImplementProse() else validJsonOutput(phaseId))
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
    assertContains(auditPrompt, "src/main/kotlin/Changed$WIDE_PROSE_CHANGED_PATHS.kt")
  }

  @Test
  fun `implement prose missing value blocks audit with a malformed-field reason`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), preplanEnvelope())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    val legacyImplementation =
      """{"contract_version":"0.6","phase_id":"implement","status":"completed","summary":"Legacy impl.",""" +
        """"produced_outputs":{"steps":["did the thing"],"narration":"free-form legacy body"}}"""
    harness.seedPhase(
      "implement",
      "completed",
      1,
      phaseAgent("implement"),
      legacyImplementation,
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "produced_outputs.value is required")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "audit" })
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("needs_user_action", record.failureDisposition?.wireValue)
  }

  @Test
  fun `a legacy handoff-envelope launch-seam block stays durably blocked on resume`() {
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

  private fun wideImplementProse(): String {
    val pathList = (1..WIDE_PROSE_CHANGED_PATHS).map { "src/main/kotlin/Changed%03d.kt".format(it) }
    val stuffed =
      """{"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
        """"completed_task_ids":["task-1"],"changed_paths":[${pathList.joinToString(",") { "\"$it\"" }}],""" +
        """"tests_executed":[],"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target state."}}"""
    val escaped = stuffed.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
      {
        "contract_version": "0.6",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Implement prose.",
        "produced_outputs": {
          "value": "$escaped"
        }
      }
    """.trimIndent()
  }

  private fun preplanEnvelope(value: String = "Fixture preplan prose."): String =
    """{"contract_version":"0.6","phase_id":"preplan","status":"completed","summary":"Prose.",""" +
      """"produced_outputs":{"value":"$value"}}"""

  private fun oversizeImplementProse(): String {
    val prose = "x".repeat(OVERSIZE_PROSE_CHARS)
    return """
      {
        "contract_version": "0.6",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Oversize implement prose.",
        "produced_outputs": {
          "value": "$prose"
        }
      }
    """.trimIndent()
  }
}

private const val OVERSIZE_PROSE_CHARS: Int = 200_000
private const val WIDE_PROSE_CHANGED_PATHS: Int = 120
