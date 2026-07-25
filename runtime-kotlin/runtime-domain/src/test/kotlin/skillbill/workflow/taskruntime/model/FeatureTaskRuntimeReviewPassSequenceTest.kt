package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewPassSequenceTest {
  @Test
  fun `an explicit mode runs at that depth on both passes and auto resolves by pass number`() {
    listOf(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.DELEGATED).forEach { pinnedMode ->
      assertEquals(listOf(pinnedMode, pinnedMode), FeatureTaskRuntimeReviewPassSequence.passes(pinnedMode))
    }
    assertEquals(
      listOf(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.AUTO),
    )
  }

  @Test
  fun `auto resolves pass one to delegated and every later pass to inline`() {
    val passOne = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 1)
    val passTwo = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 2)
    assertEquals(CodeReviewExecutionMode.DELEGATED, passOne.resolvedTier)
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
    assertEquals(
      "explicit_delegated_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 2).decidingRule,
    )
    assertEquals(
      CodeReviewExecutionMode.DELEGATED,
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 2).resolvedTier,
    )
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
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.DELEGATED, 3)
    }
  }
}
