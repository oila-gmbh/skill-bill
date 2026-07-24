@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-140 Subtask 3 (AC-001, first case): with the real Draft 2020-12 validator wired into the run
 * loop, a plan output that is structurally sound but carries an undeclared wire key — a violation only
 * `additionalProperties:false` catches, invisible to the Noop stand-in and to the typed parse — must
 * re-enter the plan phase's bounded fix loop, retry to the cap, and block durably only there. Each retry
 * prompt carries the projection rejection back so the agent can fix it. Assertions read observable
 * run-loop state (block report, launch counts, retry prompts), never internal validator calls.
 */
class RealValidatorProducerGateIntegrationTest {
  private val cap = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS

  @Test
  fun `an undeclared key in a plan projection retries to the cap and blocks only there`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") PLAN_WITH_UNDECLARED_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertEquals(cap, harness.launchedPromptPhaseOrder().count { it == "plan" }, "the plan phase must retry to the cap")
    assertContains(blocked.blockedReason, "executable_plan")
    assertContains(blocked.blockedReason, "leaked_planning_narration")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "a schema-violating plan must never advance to its consumer",
    )
  }

  @Test
  fun `no block occurs before the cap and the retry prompt carries the projection rejection`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") PLAN_WITH_UNDECLARED_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val planPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "plan" }
    // Exactly `cap` launches: the first plus (cap - 1) retries, none of which is a terminal block before
    // the last. The retries carry the schema rejection so the agent can repair the output.
    assertEquals(cap, planPrompts.size)
    assertContains(planPrompts[1], "executable_plan")
    assertContains(planPrompts[1], "leaked_planning_narration")
  }

  @Test
  fun `the same undeclared-key plan advances under the Noop validator, proving the real schema is what blocks`() {
    // The typed parse ignores unknown wire keys, so with the Noop stand-in the identical output advances
    // and the run does not block at plan. This isolates the block above to the real schema's
    // additionalProperties enforcement, not the typed parse.
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") PLAN_WITH_UNDECLARED_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "plan" },
      "under the Noop validator the undeclared-key plan advances on its first launch",
    )
  }
}

// A structurally sound executable_plan carrying one undeclared top-level wire key. Only the real
// schema's additionalProperties:false rejects it; the Noop stand-in and the typed parse accept it.
private const val PLAN_WITH_UNDECLARED_KEY: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","description":"Fixture task.","criterion_refs":["AC-001"],""" +
    """"test_obligations":["Focused test."]}],"validation_strategy":["Focused runtime tests."],""" +
    """"leaked_planning_narration":"MUST NOT SURVIVE"}}"""
