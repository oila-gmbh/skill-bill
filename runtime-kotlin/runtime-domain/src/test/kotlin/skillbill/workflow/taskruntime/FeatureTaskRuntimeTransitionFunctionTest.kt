package skillbill.workflow.taskruntime

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FeatureTaskRuntimeTransitionFunctionTest {
  private val pipeline = listOf("a", "b", "c", "d")
  private val needsFix = FeatureTaskRuntimeVerdict("needs_fix")
  private val edge = FeatureTaskRuntimeBackwardEdge(
    fromPhaseId = "c",
    triggeringVerdict = needsFix,
    destinationPhaseId = "b",
    loopId = "c-to-b",
    perEdgeCap = 2,
  )
  private val cyclic = FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = pipeline,
    backwardEdges = listOf(edge),
  )
  private val forwardOnly = FeatureTaskRuntimeTransitionDeclaration(forwardPhaseIds = pipeline)

  @Test
  fun `default advance with no matching edge progresses to the next forward index`() {
    listOf("a" to "b", "b" to "c", "c" to "d").forEach { (current, expectedNext) ->
      val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = cyclic,
        currentPhaseId = current,
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 0,
      )
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
      assertEquals(expectedNext, next.phaseId)
      assertEquals(null, next.loopId)
      assertEquals(null, next.edgeIteration)
    }
  }

  @Test
  fun `pipeline end terminates with advance`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = cyclic,
      currentPhaseId = "d",
      verdict = FeatureTaskRuntimeVerdict.ADVANCE,
      edgeIterationCount = 0,
    )
    assertIs<FeatureTaskRuntimeNextPhase.TerminalAdvance>(transition)
  }

  @Test
  fun `matching backward edge below cap re-enters the destination tagged with loop and iteration`() {
    (0 until edge.perEdgeCap).forEach { alreadyFired ->
      val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = cyclic,
        currentPhaseId = "c",
        verdict = needsFix,
        edgeIterationCount = alreadyFired,
      )
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
      assertEquals("b", next.phaseId)
      assertEquals("c-to-b", next.loopId)
      assertEquals(alreadyFired + 1, next.edgeIteration)
    }
  }

  @Test
  fun `matching backward edge at cap blocks loudly with loop id iteration and unresolved verdict`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = cyclic,
      currentPhaseId = "c",
      verdict = needsFix,
      edgeIterationCount = edge.perEdgeCap,
    )
    val block = assertIs<FeatureTaskRuntimeNextPhase.TerminalBlock>(transition)
    assertEquals("c-to-b", block.loopId)
    assertEquals(edge.perEdgeCap, block.edgeIteration)
    assertEquals(needsFix, block.unresolvedVerdict)
  }

  @Test
  fun `non-triggering verdict at the edge phase falls through to the forward edge`() {
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = cyclic,
      currentPhaseId = "c",
      verdict = FeatureTaskRuntimeVerdict.ADVANCE,
      edgeIterationCount = 5,
    )
    assertEquals("d", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `edge-free declaration is forward-only for every phase verdict and iteration`() {
    val verdicts = listOf(FeatureTaskRuntimeVerdict.ADVANCE, needsFix, FeatureTaskRuntimeVerdict("other"))
    pipeline.forEachIndexed { index, phaseId ->
      verdicts.forEach { verdict ->
        (0..3).forEach { iteration ->
          val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
            declaration = forwardOnly,
            currentPhaseId = phaseId,
            verdict = verdict,
            edgeIterationCount = iteration,
          )
          if (index < pipeline.lastIndex) {
            assertEquals(pipeline[index + 1], assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
          } else {
            assertIs<FeatureTaskRuntimeNextPhase.TerminalAdvance>(transition)
          }
        }
      }
    }
  }

  @Test
  fun `verdict round trips through its wire value`() {
    assertEquals("advance", FeatureTaskRuntimeVerdict.ADVANCE.wireValue)
    assertEquals(needsFix, FeatureTaskRuntimeVerdict.fromWire(needsFix.wireValue))
    assertEquals(FeatureTaskRuntimeVerdict.ADVANCE, FeatureTaskRuntimeVerdict.fromWire("advance"))
  }

  @Test
  fun `verdict fromWire loud-fails on a blank wire value`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> { FeatureTaskRuntimeVerdict.fromWire("  ") }
  }
}
