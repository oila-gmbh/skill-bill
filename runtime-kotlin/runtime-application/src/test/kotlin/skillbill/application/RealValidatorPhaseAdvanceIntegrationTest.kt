
package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * With the real Draft 2020-12 planning-projection validator wired into the run loop, conforming
 * preplan/plan/implement phase-prose outputs advance unchanged. Each producing phase launches exactly
 * once (no fix-loop entry), and the run reaches Completed — proving the consumer launch seam accepts
 * the same envelopes.
 */
class RealValidatorPhaseAdvanceIntegrationTest {

  @Test
  fun `conforming projections advance through plan implement and audit with the real validator`() {
    val harness = runnerHarness(
      RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator)
        .copy(agentAssignment = phasePerAgentAssignment()),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val order = harness.launchedPromptPhaseOrder()
    listOf("preplan", "plan", "implement").forEach { phaseId ->
      assertEquals(
        1,
        order.count { it == phaseId },
        "conforming $phaseId must advance on its first launch under the real validator, not retry",
      )
    }
    assertEquals(1, order.count { it == "audit" }, "audit must consume the accepted prose and settle once")
  }
}
