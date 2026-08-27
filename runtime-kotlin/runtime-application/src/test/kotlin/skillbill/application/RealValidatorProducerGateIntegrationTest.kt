@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealValidatorProducerGateIntegrationTest {
  @Test
  fun `an undeclared key in an implement receipt is absorbed and implement advances on its first launch`() {
    val harness = realValidatorHarness(RECEIPT_WITH_UNDECLARED_KEY)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement" }, "an absorbed undeclared key must not cost an attempt")
    assertTrue(launched.contains("audit"), "the absorbed receipt must advance to its consumer")
    harness.launcher.requests.forEach { request ->
      assertNoRawResponseSpan(requireNotNull(request.skillRunRequest.promptOverride), "MUST NOT SURVIVE")
    }
  }

  @Test
  fun `a wrong-typed field in an implement receipt blocks implement with the constraint kept private`() {
    val harness = realValidatorHarness(RECEIPT_WITH_WRONG_TYPED_COMPLETED_TASK_IDS)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(
      !harness.launchedPromptPhaseOrder().contains("audit"),
      "a rejected implement receipt must not reach its consumer",
    )
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "implement" }.metadata.reason,
      "string found, array expected",
    )
  }

  @Test
  fun `an absorbed undeclared key never masks a coexisting violation`() {
    val harness = realValidatorHarness(RECEIPT_WITH_UNDECLARED_KEY_AND_WRONG_TYPED_COMPLETED_TASK_IDS)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "implement" }.metadata.reason,
      "string found, array expected",
    )
  }

  private fun realValidatorHarness(implementOutput: String): RunnerHarness = runnerHarness(
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      facts(if (phaseId == "implement") implementOutput else validJsonOutput(phaseId))
    },
    agentAssignment = phasePerAgentAssignment(),
    runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
  )
}

private const val RECEIPT_WITH_UNDECLARED_KEY: String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
    """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true},""" +
    """"leaked_planning_narration":"MUST NOT SURVIVE"}}"""

private const val RECEIPT_WITH_WRONG_TYPED_COMPLETED_TASK_IDS: String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
    """"completed_task_ids":"MUST NOT SURVIVE","changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}}"""

private const val RECEIPT_WITH_UNDECLARED_KEY_AND_WRONG_TYPED_COMPLETED_TASK_IDS: String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
    """"completed_task_ids":"MUST NOT SURVIVE","changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true},""" +
    """"leaked_planning_narration":"MUST NOT SURVIVE EITHER"}}"""
