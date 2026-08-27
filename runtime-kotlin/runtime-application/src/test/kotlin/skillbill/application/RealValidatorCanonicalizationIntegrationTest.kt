@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealValidatorCanonicalizationIntegrationTest {
  @Test
  fun `a canonicalizable receipt advances with zero fix-loop attempts and the seam sees the canonical id`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") CANONICALIZABLE_RECEIPT else validJsonOutput(phaseId))
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
      "a canonicalizable receipt must advance on its first launch, consuming no fix-loop attempt",
    )

    val auditPrompt = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .first { phaseIdFromPrompt(it) == "audit" }
    assertTrue(auditPrompt.contains("task-01"), "the consumer seam must observe the canonical task id")
    assertFalse(auditPrompt.contains("Task-01"), "no pre-canonical id may survive to the consumer")
  }

  @Test
  fun `a structural violation in an otherwise canonicalizable receipt still rejects`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") CANONICALIZABLE_RECEIPT_MISSING_EVIDENCE else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(
      "implement",
      blocked.lastIncompletePhase,
      "canonicalization must not fabricate the missing field; the structural violation must reject",
    )
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
  }
}

private const val CANONICALIZABLE_RECEIPT: String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
    """"completed_task_ids":["Task-01"],"changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,""" +
    """"evidence":"  `Fixture` tree at target. "},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},""" +
    """"reconciled_state":{"reconciled":true}}}"""

private const val CANONICALIZABLE_RECEIPT_MISSING_EVIDENCE: String =
  """{"contract_version":"0.2","phase_id":"implement","status":"completed",""" +
    """"summary":"Implement output.","produced_outputs":{""" +
    """"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
    """"completed_task_ids":["Task-01"],"changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},""" +
    """"reconciled_state":{"reconciled":true}}}"""
