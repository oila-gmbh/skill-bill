package skillbill.engine

import skillbill.application.realPlanningProjectionValidator
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealValidatorCanonicalizationIntegrationTest {
  @Test
  fun `value-wrapped implement prose advances with zero fix-loop attempts`() {
    val harness = runnerHarness(
      RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator).copy(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          facts(if (phaseId == "implement") IMPLEMENT_PROSE else validJsonOutput(phaseId))
        },
        agentAssignment = phasePerAgentAssignment(),
      ),
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
