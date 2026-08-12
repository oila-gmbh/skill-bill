@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real Draft 2020-12 validator wired into the run loop, on the two fates a closed-variant violation
 * can have. An undeclared top-level wire key is canonicalized away before validation and costs no
 * fix-loop attempt (SKILL-152 AC-005); a violation the canonicalizer must not repair — a wrong-typed
 * governed field — re-enters the plan phase's bounded fix loop, retries to the cap, and blocks durably
 * only there, with each retry prompt carrying the violated constraint back (SKILL-140 AC-001, SKILL-152
 * AC-009). Assertions read observable run-loop state (block report, launch counts, retry prompts), never
 * internal validator calls.
 */
class RealValidatorProducerGateIntegrationTest {
  private val cap = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS

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
  fun `a wrong-typed field in a plan projection retries to the cap and blocks only there`() {
    val harness = realValidatorHarness(PLAN_WITH_WRONG_TYPED_TASKS)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertEquals(cap, harness.launchedPromptPhaseOrder().count { it == "plan" }, "the plan phase must retry to the cap")
    assertPrivateDiagnosticRejection(
      blocked.blockedReason,
      "producer-projection",
      "executable_plan",
      "MUST NOT SURVIVE",
    )
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "a schema-violating plan must never advance to its consumer",
    )
  }

  @Test
  fun `no block occurs before the cap and the retry prompt carries the projection rejection`() {
    val harness = realValidatorHarness(PLAN_WITH_WRONG_TYPED_TASKS)

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val planPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "plan" }
    // Exactly `cap` launches: the first plus (cap - 1) retries, none of which is a terminal block before
    // the last. Retries carry a safe diagnostic pointer without exposing the rejected payload.
    assertEquals(cap, planPrompts.size)
    // The retry prompt names the violated constraint, including the found and expected types, because that
    // is what the producer must repair. Type names are schema-side text the validator authored; the
    // field's VALUE is what stays private.
    assertRetryPromptNamesConstraint(
      planPrompts[1],
      "producer-projection",
      "string found, array expected",
    )
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(planPrompts[1], "MUST NOT SURVIVE")
  }

  @Test
  fun `an absorbed undeclared key never masks a coexisting violation`() {
    // Absorption is scoped to keys that carry no governed meaning; it must not soften the verdict on a
    // real violation that rides along with one.
    val harness = realValidatorHarness(PLAN_WITH_UNDECLARED_KEY_AND_WRONG_TYPED_TASKS)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("plan", blocked.lastIncompletePhase)
    assertEquals(cap, harness.launchedPromptPhaseOrder().count { it == "plan" })
  }

  private fun realValidatorHarness(planOutput: String) = runnerHarness(
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
