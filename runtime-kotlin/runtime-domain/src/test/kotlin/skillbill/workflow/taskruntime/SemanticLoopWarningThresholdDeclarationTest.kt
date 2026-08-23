package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SemanticLoopWarningThresholdDeclarationTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val transitions = def.transitions

  @Test
  fun `the audit gap semantic remediation edge carries the shared warning threshold`() {
    assertEquals(3, def.SEMANTIC_LOOP_WARNING_THRESHOLD)
    assertEquals(
      def.SEMANTIC_LOOP_WARNING_THRESHOLD,
      transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }.warnAfterIterations,
      "'${def.AUDIT_GAP_LOOP_ID}' must source its warning threshold from the single shared constant.",
    )
  }

  @Test
  fun `the bounded review fix edge does not declare a warning threshold`() {
    assertNull(
      transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }.warnAfterIterations,
      "'${def.REVIEW_FIX_LOOP_ID}' is bounded by a finite cap and must not attach a threshold warning.",
    )
  }

  @Test
  fun `no other backward edge declares a warning threshold`() {
    val semanticLoopIds = setOf(def.AUDIT_GAP_LOOP_ID)
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
    val auditGap = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    assertFailsWith<IllegalArgumentException> { auditGap.copy(warnAfterIterations = 0) }
  }

  @Test
  fun `the declared threshold is control-flow inert across every iteration`() {
    val cases = listOf(
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
