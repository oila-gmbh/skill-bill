package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-017: `implement_fix` keeps `perEdgeCap = 1`, but the Blocker disposition rather than
 * cap exhaustion decides termination, so a child can never both advance on cap exhaustion and pause
 * on an unresolved Blocker.
 */
class ReviewFixCapReconciliationTest {
  private val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions

  private val reviewFixEdge = transitions.backwardEdges.single { edge ->
    edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
  }

  private fun transitionAtCap(unresolvedBlockerPresent: Boolean) =
    FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = transitions,
      currentPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      edgeIterationCount = 1,
      settledVerdictsByPhaseId = mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED,
      ),
      unresolvedBlockerPresent = unresolvedBlockerPresent,
    )

  @Test
  fun `the review fix edge keeps its cap of one and its per-subtask scope`() {
    assertEquals(1, reviewFixEdge.perEdgeCap)
    assertEquals(
      FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE_UNLESS_UNRESOLVED_BLOCKER,
      reviewFixEdge.capExhaustionBehavior,
    )
  }

  @Test
  fun `a child with zero blockers still advances on cap exhaustion`() {
    val transition = transitionAtCap(unresolvedBlockerPresent = false)
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, next.phaseId)
  }

  @Test
  fun `a child with an unresolved blocker pauses rather than advancing or blocking`() {
    val transition = transitionAtCap(unresolvedBlockerPresent = true)
    val paused = assertIs<FeatureTaskRuntimeNextPhase.TerminalPause>(transition)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, paused.loopId)
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, paused.unresolvedVerdict)
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

  @Test
  fun `the first fix attempt still runs before any pause`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = transitions,
      currentPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      edgeIterationCount = 0,
      unresolvedBlockerPresent = true,
    )
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX, next.phaseId)
  }
}
