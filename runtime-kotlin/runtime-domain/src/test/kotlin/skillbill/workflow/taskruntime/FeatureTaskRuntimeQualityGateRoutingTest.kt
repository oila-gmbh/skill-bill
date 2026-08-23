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
  fun `selected gate phase maps build and validate stamps to their gate phases`() {
    assertEquals(
      def.PHASE_BUILD,
      FeatureTaskRuntimeQualityGateRouting.selectedGatePhase(FeatureTaskRuntimeQualityGateSelection.BUILD),
    )
    assertEquals(
      def.PHASE_VALIDATE,
      FeatureTaskRuntimeQualityGateRouting.selectedGatePhase(FeatureTaskRuntimeQualityGateSelection.VALIDATE),
    )
  }

  @Test
  fun `default forward path advances review to verify_findings skipping loop-only build`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_REVIEW,
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      edgeIterationCount = 0,
    )
    assertEquals(def.PHASE_VERIFY_FINDINGS, assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `build-selected path remaps verify_findings advance to validate into build`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_VERIFY_FINDINGS,
      verdict = FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
      edgeIterationCount = 0,
    )
    assertEquals(def.PHASE_VALIDATE, assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
    val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
      def.PHASE_VERIFY_FINDINGS,
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
    assertEquals(def.PHASE_VALIDATE, assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
    // Same order as FeatureTaskRuntimeRunLoop.nextPhaseAfter: review remap then build remap.
    val routed = FeatureTaskRuntimeQualityGateRouting.applyAfterBuild(
      def.PHASE_BUILD,
      FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
        def.PHASE_BUILD,
        transition,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
      ),
    )
    assertEquals(def.PHASE_WRITE_HISTORY, assertIs<FeatureTaskRuntimeNextPhase.Next>(routed).phaseId)
  }

  @Test
  fun `build-selected leave-build must not remap validate back to build`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = def.transitions,
      currentPhaseId = def.PHASE_BUILD,
      verdict = FeatureTaskRuntimeVerdict.SATISFIED,
      edgeIterationCount = 0,
    )
    val afterReview = FeatureTaskRuntimeQualityGateRouting.applyAfterReview(
      def.PHASE_BUILD,
      transition,
      FeatureTaskRuntimeQualityGateSelection.BUILD,
    )
    assertEquals(
      def.PHASE_VALIDATE,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(afterReview).phaseId,
      "leaving build must keep validate so applyAfterBuild can advance to write_history",
    )
  }
}
