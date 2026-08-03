package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.DelegatedReviewModeRemovedException
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewPassSequenceTest {
  @Test
  fun `an explicit mode runs at that depth on both passes and auto resolves by pass number`() {
    assertEquals(
      listOf(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.INLINE),
    )
    assertEquals(
      listOf(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.AUTO),
    )
  }

  @Test
  fun `auto resolves every pass to inline`() {
    val passOne = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 1)
    val passTwo = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 2)
    assertEquals(CodeReviewExecutionMode.INLINE, passOne.resolvedTier)
    assertEquals(CodeReviewExecutionMode.INLINE, passTwo.resolvedTier)
  }

  @Test
  fun `auto never resolves silently and names the deciding rule`() {
    listOf(1, 2).forEach { passNumber ->
      val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, passNumber)
      assertTrue(
        resolution.decidingRule.startsWith("auto_depth_by_pass_number"),
        "Auto must report the named rule that decided the tier, got '${resolution.decidingRule}'.",
      )
    }
  }

  @Test
  fun `an explicit tier overrides auto and is recorded as an override`() {
    assertEquals(
      "explicit_inline_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.INLINE, 1).decidingRule,
    )
  }

  @Test
  fun `a pinned delegated mode fails loudly instead of resolving a tier or degrading to inline`() {
    val failure = assertFailsWith<DelegatedReviewModeRemovedException> {
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 1)
    }
    assertTrue(
      failure.message.orEmpty().contains("External delegated code review was removed"),
      "the typed removal error must state the subsystem is gone, got '${failure.message}'.",
    )
    assertFailsWith<DelegatedReviewModeRemovedException> {
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.DELEGATED)
    }
  }

  @Test
  fun `resolving auto does not mutate the pinned code review mode`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )
    FeatureTaskRuntimeReviewPassSequence.resolveForPass(state.codeReviewMode, 2)
    assertEquals(CodeReviewExecutionMode.AUTO, state.codeReviewMode)
    assertEquals("auto", state.toArtifactMap()["code_review_mode"])
  }

  @Test
  fun `a pass beyond the durable cap fails loudly`() {
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, 3)
    }
  }
}
