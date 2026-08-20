package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
  fun `the review fix edge declares cap one with advance exhaustion`() {
    assertEquals(1, reviewFixEdge.perEdgeCap)
    assertEquals(
      FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
      reviewFixEdge.capExhaustionBehavior,
    )
  }

  @Test
  fun `the first changes_requested re-enters implement_fix`() {
    val transition = transitionAt(0, FeatureTaskRuntimeVerdict.CHANGES_REQUESTED)
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX, next.phaseId)
    assertEquals(1, next.edgeIteration)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, next.loopId)
  }

  @Test
  fun `a second changes_requested advances to validate without re-review`() {
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
      transitionAt(1, FeatureTaskRuntimeVerdict.CHANGES_REQUESTED),
    )
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, next.phaseId)
    assertEquals(null, next.loopId)
  }

  @Test
  fun `an approved review advances to validate`() {
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
      transitionAt(0, FeatureTaskRuntimeVerdict.APPROVED),
    )
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, next.phaseId)
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
