@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-140 Subtask 3 (AC-001, second case): a plan output that is lexically non-conforming but
 * structurally sound — an uppercase task_id and a backtick-bearing, whitespace-padded description — is
 * normalized by the shared canonicalization inside the parse function and advances WITHOUT consuming a
 * fix-loop attempt. The consumer launch seam observes the canonical ids. A structural violation in the
 * same output still rejects, proving canonicalization normalizes trivia but never synthesizes or coerces
 * a missing obligation. Canonicalization is exercised only through the shared validation function (the
 * real validator wired into the run loop), never a seam-local rewrite in the test.
 */
class RealValidatorCanonicalizationIntegrationTest {
  private val cap = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS

  @Test
  fun `a canonicalizable plan advances with zero fix-loop attempts and the seam sees the canonical id`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") CANONICALIZABLE_PLAN else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "plan" },
      "a canonicalizable plan must advance on its first launch, consuming no fix-loop attempt",
    )

    val implementPrompt = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .first { phaseIdFromPrompt(it) == "implement" }
    // The launch seam canonicalizes the same way the producer gate did: the consumer sees the canonical id.
    assertTrue(implementPrompt.contains("task-1"), "the consumer seam must observe the canonical task id")
    assertFalse(implementPrompt.contains("TASK-1"), "no pre-canonical id may survive to the consumer")
  }

  @Test
  fun `a structural violation in an otherwise canonicalizable plan still rejects to the cap`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") CANONICALIZABLE_PLAN_MISSING_OBLIGATION else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertEquals(
      cap,
      harness.launchedPromptPhaseOrder().count { it == "plan" },
      "canonicalization must not fabricate the missing obligation; the structural violation retries to the cap",
    )
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "a structurally invalid plan must never advance to its consumer",
    )
  }
}

// Structurally sound but lexically non-conforming: uppercase task_id and a backtick-bearing,
// whitespace-padded description. Canonicalization lowercases the id and strips backticks/trims the
// summary so the strict schema accepts it; without canonicalization the taskId pattern and the
// compactSummary anti-paste guard would both reject.
private const val CANONICALIZABLE_PLAN: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"TASK-1","description":"  `Fixture` task. ","criterion_refs":["AC-001"],""" +
    """"test_obligations":["Focused test."]}],"validation_strategy":["Focused runtime tests."]}}"""

// The same canonicalizable id but with the required test_obligations omitted. Canonicalization never
// synthesizes a missing field, so the schema still rejects it.
private const val CANONICALIZABLE_PLAN_MISSING_OBLIGATION: String =
  """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{""" +
    """"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"TASK-1","description":"Fixture task.","criterion_refs":["AC-001"]}],""" +
    """"validation_strategy":["Focused runtime tests."]}}"""
