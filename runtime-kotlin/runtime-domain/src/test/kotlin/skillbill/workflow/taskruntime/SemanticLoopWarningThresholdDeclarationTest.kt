package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * SKILL-157 subtask 2: the semantic remediation loops declare one shared warning threshold, and that
 * declaration is inert control flow. A threshold that leaked into the transition function would turn
 * an advisory warning into a hidden cap, which is exactly what the unbounded loops exist to prevent.
 */
class SemanticLoopWarningThresholdDeclarationTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val transitions = def.transitions

  @Test
  fun `both semantic remediation edges carry the shared warning threshold`() {
    assertEquals(3, def.SEMANTIC_LOOP_WARNING_THRESHOLD)
    listOf(def.REVIEW_FIX_LOOP_ID, def.AUDIT_GAP_LOOP_ID).forEach { loopId ->
      assertEquals(
        def.SEMANTIC_LOOP_WARNING_THRESHOLD,
        transitions.backwardEdges.single { it.loopId == loopId }.warnAfterIterations,
        "'$loopId' must source its warning threshold from the single shared constant.",
      )
    }
  }

  @Test
  fun `no other backward edge declares a warning threshold`() {
    val semanticLoopIds = setOf(def.REVIEW_FIX_LOOP_ID, def.AUDIT_GAP_LOOP_ID)
    transitions.backwardEdges
      .filterNot { it.loopId in semanticLoopIds }
      .forEach { edge ->
        assertNull(
          edge.warnAfterIterations,
          "'${edge.loopId}' is bounded by a finite cap and must not attach a threshold warning.",
        )
      }
  }

  @Test
  fun `a threshold below one is rejected at construction`() {
    val reviewFix = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    assertFailsWith<IllegalArgumentException> { reviewFix.copy(warnAfterIterations = 0) }
  }

  @Test
  fun `the declared threshold is control-flow inert across every iteration`() {
    val cases = listOf(
      def.PHASE_REVIEW to FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.GAPS_FOUND,
    )
    cases.forEach { (phaseId, verdict) ->
      val withThreshold = transitions
      val withoutThreshold = transitions.copy(
        backwardEdges = transitions.backwardEdges.map { it.copy(warnAfterIterations = null) },
      )
      (1..10).forEach { iteration ->
        assertEquals(
          nextTransition(withoutThreshold, phaseId, verdict, iteration),
          nextTransition(withThreshold, phaseId, verdict, iteration),
          "Iteration $iteration of '$phaseId' must transition identically with and without a threshold.",
        )
      }
    }
  }

  private fun nextTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    iteration: Int,
  ) = FeatureTaskRuntimeTransitionFunction.nextTransition(
    declaration = declaration,
    currentPhaseId = phaseId,
    verdict = verdict,
    edgeIterationCount = iteration,
    context = FeatureTaskRuntimeTransitionContext(
      settledVerdictsByPhaseId = mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED),
    ),
  )
}
