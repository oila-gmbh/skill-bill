package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealValidatorProducerGateIntegrationTest {
  @Test
  fun `completed implement with only value advances to audit`() {
    val harness = realValidatorHarness(IMPLEMENT_VALUE_ONLY)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement" })
    assertTrue(launched.contains("audit"))
  }

  @Test
  fun `legacy keys beside value complete implement without producer-projection re-entry`() {
    val harness = realValidatorHarness(IMPLEMENT_LEGACY_KEYS_BESIDE_VALUE)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `malformed JSON stuffed inside value still completes implement`() {
    val harness = realValidatorHarness(IMPLEMENT_MALFORMED_JSON_IN_VALUE)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `blank value blocks implement at the phase-output schema`() {
    val harness = realValidatorHarness(IMPLEMENT_BLANK_VALUE, useRealPhaseOutputValidator = true)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

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

  private fun realValidatorHarness(
    implementOutput: String,
    useRealPhaseOutputValidator: Boolean = false,
  ): RunnerHarness = runnerHarness(
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      facts(if (phaseId == "implement") implementOutput else validJsonOutput(phaseId))
    },
    validator = if (useRealPhaseOutputValidator) {
      realFeatureTaskRuntimePhaseOutputValidator
    } else {
      AlwaysValidValidator
    },
    agentAssignment = phasePerAgentAssignment(),
    runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
  )
}

private const val IMPLEMENT_VALUE_ONLY: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"value":"Dense implement prose for audit."}}"""

private const val IMPLEMENT_LEGACY_KEYS_BESIDE_VALUE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"value":"Dense implement prose with leftover receipt keys",""" +
    """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
    """"reconciled_state":{"reconciled":true}}}"""

private const val IMPLEMENT_MALFORMED_JSON_IN_VALUE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"value":"{partial json without closing"}}"""

private const val IMPLEMENT_BLANK_VALUE: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{"value":"   "}}"""
