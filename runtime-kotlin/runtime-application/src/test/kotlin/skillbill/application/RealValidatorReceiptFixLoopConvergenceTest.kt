package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Implement receipt fix-loop convergence is gone with the planning-projection producer gate. Missing
 * or blank value fails the phase-output schema at implement; conforming prose advances once.
 */
class RealValidatorReceiptFixLoopConvergenceTest {
  @Test
  fun `conforming implement prose advances without a fix-loop relaunch`() {
    val harness = runnerHarness(RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator).copy(launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") IMPLEMENT_PROSE else validJsonOutput(phaseId))
      }, agentAssignment = phasePerAgentAssignment()))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
  }

  @Test
  fun `missing value blocks implement without reaching audit`() {
    assertBlockedAtImplement(IMPLEMENT_MISSING_VALUE)
  }

  @Test
  fun `blank value blocks implement without reaching audit`() {
    assertBlockedAtImplement(IMPLEMENT_BLANK_VALUE)
  }

  private fun assertBlockedAtImplement(malformed: String) {
    val harness = runnerHarness(RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator).copy(launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") malformed else validJsonOutput(phaseId))
      }, validator = realFeatureTaskRuntimePhaseOutputValidator, agentAssignment = phasePerAgentAssignment()))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertTrue(
      blocked.blockedReason.contains("value", ignoreCase = true) ||
        harness.io.database.rejectedDiagnostics().any {
          it.metadata.phaseId == "implement" && it.metadata.reason.contains("value", ignoreCase = true)
        },
    )
    assertTrue(!harness.launchedPromptPhaseOrder().contains("audit"))
  }
}

private const val IMPLEMENT_PROSE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"value":"Fixture implement prose for audit."}}"""

private const val IMPLEMENT_MISSING_VALUE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"prompt":"optional only"}}"""

private const val IMPLEMENT_BLANK_VALUE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"value":"   "}}"""
