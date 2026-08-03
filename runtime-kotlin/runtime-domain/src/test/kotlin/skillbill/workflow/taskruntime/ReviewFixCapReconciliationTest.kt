package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-157: `review_fix` declares no finite cap. The unresolved Blocker signal alone drives
 * re-entry into `implement_fix`, and the first Blocker-free review advances however many remediation
 * rounds preceded it.
 */
class ReviewFixCapReconciliationTest {
  private val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions

  private val reviewFixEdge = transitions.backwardEdges.single { edge ->
    edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
  }

  private fun transitionAt(iteration: Int, verdict: FeatureTaskRuntimeVerdict) =
    FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = transitions,
      currentPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      verdict = verdict,
      edgeIterationCount = iteration,
      context = FeatureTaskRuntimeTransitionContext(
        settledVerdictsByPhaseId = mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED,
        ),
      ),
    )

  @Test
  fun `the review fix edge declares no finite cap and keeps its per-subtask scope`() {
    assertNull(reviewFixEdge.perEdgeCap, "A finite cap would re-impose the two-pass ceiling.")
    assertEquals(
      FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
      reviewFixEdge.capExhaustionBehavior,
      "An uncapped edge never exhausts, so it must not declare an advance-or-pause exhaustion rule.",
    )
  }

  @Test
  fun `an unresolved blocker re-enters implement_fix at every iteration count`() {
    listOf(0, 1, 3, 9, 24, 199).forEach { consumed ->
      val transition = transitionAt(consumed, FeatureTaskRuntimeVerdict.CHANGES_REQUESTED)
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition,
        "Iteration ${consumed + 1} must re-enter the fix loop, not settle.",
      )
      assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX, next.phaseId)
      assertEquals(consumed + 1, next.edgeIteration)
      assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, next.loopId)
    }
  }

  @Test
  fun `the first blocker-free review advances however many rounds preceded it`() {
    listOf(0, 1, 4, 12, 40).forEach { consumed ->
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transitionAt(consumed, FeatureTaskRuntimeVerdict.APPROVED),
      )
      assertEquals(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        next.phaseId,
        "An approved review after $consumed remediation rounds must advance normally.",
      )
    }
  }

  @Test
  fun `the child definition carries paused as a non-terminal status`() {
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    assertTrue("paused" in definition.workflowStatuses)
    assertTrue(
      "paused" !in definition.terminalStatuses,
      "Pause is SKILL-141's resumable status, not a terminal one.",
    )
  }
}
