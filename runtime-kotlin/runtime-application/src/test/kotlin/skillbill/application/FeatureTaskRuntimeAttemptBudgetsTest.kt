package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeAttemptBudgets
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeAttemptBudgetsTest {
  @Test
  fun `output-gate retries cap at two and process-failure stays at three`() {
    assertEquals(2, FeatureTaskRuntimeAttemptBudgets.MAX_OUTPUT_GATE_RETRY_ATTEMPTS)
    assertEquals(2, FeatureTaskRuntimeAttemptBudgets.MAX_FORMAT_RETRY_ATTEMPTS)
    assertEquals(3, FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS)
  }

  @Test
  fun `a phase that keeps dying before its output gate blocks on its own budget, not a repair loop`() {
    val below = FeatureTaskRuntimeAttemptBudgets.processFailureBlockReason(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      processFailureCount = FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS - 1,
      lastFailureReason = "agent exited with non-zero status 1",
    )
    assertEquals(null, below)

    val blocked = requireNotNull(
      FeatureTaskRuntimeAttemptBudgets.processFailureBlockReason(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        processFailureCount = FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS,
        lastFailureReason = "agent exited with non-zero status 1",
      ),
    )
    assertContains(blocked, "failed to execute")
    assertContains(blocked, "No repair attempt was consumed.")
    assertContains(blocked, "agent exited with non-zero status 1")
    assertTrue(!blocked.contains("invalid output"), blocked)
    assertTrue(!blocked.contains("fix loop"), blocked)
  }

  @Test
  fun `the second schema-invalid output blocks instead of relaunching`() {
    assertEquals(
      null,
      FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason("audit", 1),
    )
    val reason = FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason("audit", 2)
    assertContains(requireNotNull(reason), "cap=2")
    assertContains(reason, "blocks rather than relaunching")
  }
}
