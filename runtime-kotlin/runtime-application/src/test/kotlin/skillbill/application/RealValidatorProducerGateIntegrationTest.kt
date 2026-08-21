@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real Draft 2020-12 validator wired into the run loop, on the two fates a closed-variant violation
 * can have. An undeclared top-level wire key is canonicalized away before validation and costs the phase
 * nothing (SKILL-152 AC-005); a violation the canonicalizer must not repair — a wrong-typed governed
 * field — rejects, and under a one-attempt output-gate budget that settles the run at plan with the
 * violated constraint kept on the private diagnostic (SKILL-140 AC-001, SKILL-152 AC-009). Assertions
 * read observable run-loop state (launch counts, blocked reasons), never internal validator calls.
 */
class RealValidatorProducerGateIntegrationTest {
  @Test
  fun `an undeclared key in a plan projection is absorbed and the plan advances on its first launch`() {
    val harness = realValidatorHarness(PLAN_WITH_UNDECLARED_KEY)

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "plan" }, "an absorbed undeclared key must not cost an attempt")
    assertTrue(launched.contains("implement"), "the absorbed plan must advance to its consumer")
    harness.launcher.requests.forEach { request ->
      assertNoRawResponseSpan(requireNotNull(request.skillRunRequest.promptOverride), "MUST NOT SURVIVE")
    }
  }

  @Test
  fun `a wrong-typed field in a plan projection blocks plan with the constraint kept private`() {
    val harness = realValidatorHarness(PLAN_WITH_WRONG_TYPED_TASKS)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("plan", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "plan" })
    assertTrue(
      !harness.launchedPromptPhaseOrder().contains("implement"),
      "a rejected plan must not reach its consumer",
    )
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "plan" }.metadata.reason,
      "string found, array expected",
    )
  }

  @Test
  fun `an absorbed undeclared key never masks a coexisting violation`() {
    val harness = realValidatorHarness(PLAN_WITH_UNDECLARED_KEY_AND_WRONG_TYPED_TASKS)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "plan" }.metadata.reason,
      "string found, array expected",
    )
  }

  private fun realValidatorHarness(planOutput: String): RunnerHarness = runnerHarness(
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      facts(if (phaseId == "plan") planOutput else validJsonOutput(phaseId))
    },
    agentAssignment = phasePerAgentAssignment(),
    runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
  )
}

// A structurally sound executable_plan carrying one undeclared top-level wire key. It carries no governed
// meaning for any contract, so the canonicalizer discards it before the schema ever sees it.
private const val PLAN_WITH_UNDECLARED_KEY: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","description":"Fixture task.","criterion_refs":["AC-001"],""" +
    """"test_obligations":["Focused test."]}],"validation_strategy":["Focused runtime tests."],""" +
    """"leaked_planning_narration":"MUST NOT SURVIVE"}}"""

// A governed field of the wrong type: the canonicalizer passes an unexpected shape through untouched, so
// the schema rejects it and the fix loop owns the repair.
private const val PLAN_WITH_WRONG_TYPED_TASKS: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":"MUST NOT SURVIVE","validation_strategy":["Focused runtime tests."]}}"""

private const val PLAN_WITH_UNDECLARED_KEY_AND_WRONG_TYPED_TASKS: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":"MUST NOT SURVIVE","validation_strategy":["Focused runtime tests."],""" +
    """"leaked_planning_narration":"MUST NOT SURVIVE EITHER"}}"""
