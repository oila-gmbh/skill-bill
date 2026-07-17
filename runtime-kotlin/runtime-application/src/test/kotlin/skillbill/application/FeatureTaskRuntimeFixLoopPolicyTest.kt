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
 * Validation is also bounded: validation failures are repair work and earn
 * retries up to the cap, then block loudly instead of looping forever.
 */
class FeatureTaskRuntimeFixLoopPolicyTest {
  @Test
  fun `the mutating implement phase is a bounded fix-loop under the idempotency contract`() {
    // SKILL-85 Subtask 3 (AC2): implement now participates in the bounded fix loop because the
    // mutating-phase idempotency contract makes re-entry safe (reconcile-to-target, no double-apply).
    // It retries to the cap, then blocks loudly with the fix-loop exhaustion reason.
    (1 until FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS).forEach { iteration ->
      val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
        FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          currentIteration = iteration,
        ),
        "implement must Retry below the cap at iteration $iteration",
      )
      assertEquals(iteration + 1, retry.nextIteration)
      assertEquals(iteration, retry.fixLoopIteration)
    }
    val blocked = assertIs<FeatureTaskRuntimeFixLoopDecision.Block>(
      FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        currentIteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      ),
      "implement must Block once the bounded cap is reached",
    )
    assertTrue(
      blocked.blockedReason.contains("exhausted the bounded fix loop"),
      "block reason for implement must name fix-loop exhaustion, not a no-fix-loop fence",
    )
  }

  @Test
  fun `a budget-exhausted implement resume re-blocks rather than relaunching`() {
    // implement is bounded, so a resume past the cap re-blocks.
    assertTrue(
      FeatureTaskRuntimeFixLoopPolicy.blockReasonIfBudgetExhausted(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS + 1,
      )?.contains("exhausted the bounded fix loop") == true,
      "a resumed implement past the cap must re-block on the bounded budget",
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
  fun `validation fix-loop is bounded and blocks after the cap instead of looping`() {
    (1 until FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS).forEach { iteration ->
      val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
        FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          currentIteration = iteration,
        ),
        "validation must Retry below the cap at iteration $iteration",
      )
      assertEquals(iteration + 1, retry.nextIteration)
      assertEquals(iteration, retry.fixLoopIteration)
    }
    val blocked = assertIs<FeatureTaskRuntimeFixLoopDecision.Block>(
      FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        currentIteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      ),
      "validation must Block once the bounded cap is reached instead of looping forever",
    )
    assertTrue(
      blocked.blockedReason.contains("exhausted the bounded fix loop"),
      "block reason for validation must name fix-loop exhaustion",
    )
    assertTrue(
      FeatureTaskRuntimeFixLoopPolicy.blockReasonIfBudgetExhausted(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        iteration = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS + 1,
      )?.contains("exhausted the bounded fix loop") == true,
      "a resumed validation past the cap must re-block on the bounded budget",
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
