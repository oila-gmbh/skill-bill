package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Implement no longer owns a planning-projection receipt, so receipt canonicalization is not on the
 * producer path. Conforming phase-prose implement still advances under the real validator.
 */
class RealValidatorCanonicalizationIntegrationTest {
  @Test
  fun `value-wrapped implement prose advances with zero fix-loop attempts`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") IMPLEMENT_PROSE else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "conforming implement prose must advance on its first launch",
    )
  }
}

private const val IMPLEMENT_PROSE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"value":"Fixture implement prose for audit."}}"""
