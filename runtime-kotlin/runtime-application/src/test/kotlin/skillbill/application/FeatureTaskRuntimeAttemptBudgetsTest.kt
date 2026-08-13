package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeAttemptBudgets
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Process-death and malformed-serialization keep their own budgets so a dead process or unparseable
 * payload cannot loop forever. Schema-invalid retries are uncapped and live on the phase predicate,
 * not on these budgets.
 */
class FeatureTaskRuntimeAttemptBudgetsTest {
  @Test
  fun `the process-failure and malformed-output caps stay pinned at three`() {
    assertEquals(3, FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS)
    assertEquals(3, FeatureTaskRuntimeAttemptBudgets.MAX_FORMAT_RETRY_ATTEMPTS)
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
  fun `malformed output uses a separate bounded formatting budget`() {
    (1 until FeatureTaskRuntimeAttemptBudgets.MAX_FORMAT_RETRY_ATTEMPTS).forEach { attempt ->
      assertEquals(
        null,
        FeatureTaskRuntimeAttemptBudgets.malformedOutputBlockReason("audit", attempt),
      )
    }
    val reason = FeatureTaskRuntimeAttemptBudgets.malformedOutputBlockReason(
      "audit",
      FeatureTaskRuntimeAttemptBudgets.MAX_FORMAT_RETRY_ATTEMPTS,
    )
    assertContains(requireNotNull(reason), "semantic repair attempts were not consumed")
  }
}
