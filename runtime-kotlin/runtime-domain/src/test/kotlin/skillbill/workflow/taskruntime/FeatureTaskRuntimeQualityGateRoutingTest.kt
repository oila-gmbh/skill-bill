package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeQualityGateRoutingTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition

  @Test
  fun `default forward path advances review to validate skipping loop-only build`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_REVIEW,
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      edgeIterationCount = 0,
    )
    assertEquals(def.PHASE_VALIDATE, assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `build-selected path advances review to build not validate`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_REVIEW,
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      edgeIterationCount = 0,
    )
    val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
      def.PHASE_REVIEW,
      transition,
      FeatureTaskRuntimeQualityGateSelection.BUILD,
    )
    assertEquals(def.PHASE_BUILD, assertIs<FeatureTaskRuntimeNextPhase.Next>(routed).phaseId)
  }

  @Test
  fun `build phase routes forward to write_history skipping validate`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_BUILD,
      verdict = FeatureTaskRuntimeVerdict.SATISFIED,
      edgeIterationCount = 0,
    )
    val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterBuild(def.PHASE_BUILD, transition)
    assertEquals(def.PHASE_WRITE_HISTORY, assertIs<FeatureTaskRuntimeNextPhase.Next>(routed).phaseId)
  }
}
