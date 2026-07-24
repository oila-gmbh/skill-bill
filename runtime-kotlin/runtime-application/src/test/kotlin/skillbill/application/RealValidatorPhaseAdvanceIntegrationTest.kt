@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * SKILL-140 Subtask 3 (AC-001, third case): with the real Draft 2020-12 planning-projection validator
 * wired into the run loop, conforming preplan/plan/implement projections advance unchanged through the
 * producer gate and the downstream launch seam. Each producing phase launches exactly once (no
 * fix-loop entry), and the run reaches Completed — proving the producer-gate-accepted envelope is also
 * accepted at the consumer launch seam, since a rejected seam parse would block the run.
 */
class RealValidatorPhaseAdvanceIntegrationTest {

  @Test
  fun `conforming projections advance through plan implement and audit with the real validator`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val order = harness.launchedPromptPhaseOrder()
    // Each producing phase advances on its first launch: no producer-gate rejection, no fix-loop retry.
    listOf("preplan", "plan", "implement").forEach { phaseId ->
      assertEquals(
        1,
        order.count { it == phaseId },
        "conforming $phaseId must advance on its first launch under the real validator, not retry",
      )
    }
    // audit consumes the plan_commitment and implementation_receipt the producer gate accepted; it runs
    // once and clears, proving the launch-seam parse accepts the same envelopes.
    assertEquals(1, order.count { it == "audit" }, "audit must consume the accepted projections and settle once")
  }
}
