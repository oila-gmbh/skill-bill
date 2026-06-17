package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-65 Subtask 5 (AC2) regression guardrail: a phase cannot advance without a
 * validated output. The progression gate is enforced by
 * [FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure]: on a schema-gate failure
 * it returns [FeatureTaskRuntimeFixLoopDecision.Block] (never a silent advance)
 * for a non-fix-loop phase immediately, and for bounded fix-loop phases only
 * after the [FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS] cap.
 * Validation is the exception: validation failures are repair work and keep
 * retrying instead of blocking the task.
 */
class FeatureTaskRuntimeFixLoopPolicyTest {
  @Test
  fun `the mutating implement phase blocks on first failed output without advancing`() {
    val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      currentIteration = 1,
    )
    val blocked = assertIs<FeatureTaskRuntimeFixLoopDecision.Block>(
      decision,
      "the mutating implement phase must Block on a failed output, never advance",
    )
    assertTrue(
      blocked.blockedReason.contains("does not participate in a fix loop"),
      "block reason for implement must name the no-fix-loop cause",
    )
  }

  @Test
  fun `fix-loop phase retries up to the cap then blocks instead of advancing`() {
    val fixLoopPhases = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    )
    fixLoopPhases.forEach { phaseId ->
      (1 until FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS).forEach { iteration ->
        val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
          FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(phaseId, currentIteration = iteration),
          "fix-loop phase '$phaseId' must Retry below the cap at iteration $iteration",
        )
        assertEquals(iteration + 1, retry.nextIteration)
        assertEquals(iteration, retry.fixLoopIteration)
      }
      val blocked = assertIs<FeatureTaskRuntimeFixLoopDecision.Block>(
        FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
          phaseId,
          currentIteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
        ),
        "fix-loop phase '$phaseId' must Block once the cap is reached",
      )
      assertTrue(
        blocked.blockedReason.contains("exhausted the bounded fix loop"),
        "block reason for '$phaseId' must name fix-loop exhaustion",
      )
    }
  }

  @Test
  fun `validation fix-loop keeps retrying after the bounded phase cap`() {
    val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
      FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        currentIteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      ),
      "validation must keep retrying instead of blocking at the bounded phase cap",
    )

    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS + 1, retry.nextIteration)
    assertEquals(FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS, retry.fixLoopIteration)
    assertEquals(
      null,
      FeatureTaskRuntimeFixLoopPolicy.blockReasonIfBudgetExhausted(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        iteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS + 1,
      ),
    )
  }

  @Test
  fun `decideAfterFailure rejects an initial-attempt boundary below the 1-based floor`() {
    listOf(0, -1).forEach { invalidIteration ->
      assertFailsWith<IllegalArgumentException>(
        "currentIteration $invalidIteration (< 1) must fail the 1-based precondition",
      ) {
        FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          currentIteration = invalidIteration,
        )
      }
    }
  }
}
