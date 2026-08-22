package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * SKILL-157/SKILL-205: exactly two backward edges are uncapped — `review_fix` and `audit_gap` — and
 * widening either must not widen any other bounded edge. Every remaining backward edge keeps its
 * finite cap and its cap-exhaustion behavior. The topology stays uncapped for both loops; SKILL-205
 * moved the audit_gap bound out of the topology into the runtime (the warn-threshold pause), so the
 * transition function still returns a Next edge above iteration three while the application layer
 * pauses. The application-level pause tests own the control-flow expectation.
 */
class UnboundedRemediationLoopRegressionTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val transitions = def.transitions

  private val auditSatisfied = mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED)

  private fun transition(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    iteration: Int,
    settled: Map<String, FeatureTaskRuntimeVerdict> = emptyMap(),
  ) = FeatureTaskRuntimeTransitionFunction.nextTransition(
    declaration = transitions,
    currentPhaseId = phaseId,
    verdict = verdict,
    edgeIterationCount = iteration,
    context = FeatureTaskRuntimeTransitionContext(settledVerdictsByPhaseId = settled),
  )

  @Test
  fun `audit_gap topology stays uncapped and returns the Next edge above iteration three`() {
    // The transition function is the topology: it still returns a Next edge at every gap iteration,
    // and the edge's perEdgeCap stays null. The runtime (not this topology) applies the warn-threshold
    // pause above iteration three; see FeatureTaskRuntimeLoopWarningThresholdTest and
    // FeatureTaskRuntimeAuditGapLoopTest for the application-level pause expectation.
    listOf(0, 3, 4, 11, 30).forEach { consumed ->
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.GAPS_FOUND, consumed),
      )
      assertEquals(def.PHASE_IMPLEMENT, next.phaseId, "Gap iteration ${consumed + 1} must reopen implementation.")
      assertEquals(def.AUDIT_GAP_LOOP_ID, next.loopId)
    }
    assertNull(transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }.perEdgeCap)
  }

  @Test
  fun `a satisfied audit advances to review at any gap iteration count`() {
    listOf(0, 4, 17).forEach { consumed ->
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED, consumed, auditSatisfied),
      )
      assertEquals(def.PHASE_REVIEW, next.phaseId)
    }
  }

  @Test
  fun `only the two remediation loops are uncapped`() {
    assertEquals(
      setOf(def.REVIEW_FIX_LOOP_ID, def.AUDIT_GAP_LOOP_ID),
      transitions.backwardEdges.filter { it.perEdgeCap == null }.map { it.loopId }.toSet(),
    )
  }

  @Test
  fun `every record-regeneration edge keeps its finite cap and blocking exhaustion`() {
    val regenerationEdges = transitions.backwardEdges.filter { def.isRegenerationLoopId(it.loopId) }
    assertEquals(3, regenerationEdges.size, "All three quarantine-and-regenerate edges must survive.")
    assertEquals(2, def.MAX_RECORD_REGENERATION_ATTEMPTS, "The regeneration cap is pinned, not derived.")
    regenerationEdges.forEach { edge ->
      assertEquals(
        def.MAX_RECORD_REGENERATION_ATTEMPTS,
        edge.perEdgeCap,
        "'${edge.loopId}' must keep its bounded regeneration cap.",
      )
      assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.BLOCK, edge.capExhaustionBehavior)
    }
  }

  @Test
  fun `every capped edge keeps its cap and gains no threshold warning`() {
    transitions.backwardEdges
      .filter { it.perEdgeCap != null }
      .forEach { edge ->
        assertEquals(
          def.MAX_RECORD_REGENERATION_ATTEMPTS,
          edge.perEdgeCap,
          "'${edge.loopId}' must keep its finite cap.",
        )
        assertNull(
          edge.warnAfterIterations,
          "'${edge.loopId}' is capped, so a threshold warning would be misleading.",
        )
      }
  }

  @Test
  fun `an exhausted regeneration edge still blocks durably`() {
    val transition = transition(
      def.PHASE_PLAN,
      FeatureTaskRuntimeVerdict.RECORD_REJECTED,
      def.MAX_RECORD_REGENERATION_ATTEMPTS,
    )
    val blocked = assertIs<FeatureTaskRuntimeNextPhase.TerminalBlock>(transition)
    assertEquals(def.PREPLAN_REGENERATION_LOOP_ID, blocked.loopId)
  }
}
