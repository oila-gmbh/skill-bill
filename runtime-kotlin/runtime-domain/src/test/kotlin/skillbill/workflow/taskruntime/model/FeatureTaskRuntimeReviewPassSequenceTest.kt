package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewPassSequenceTest {
  @Test
  fun `auto fans out on pass one and runs the single prompt on the remediation pass`() {
    assertEquals(
      listOf(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.AUTO),
    )
  }

  @Test
  fun `an explicit mode owns pass one and the remediation pass always runs inline`() {
    assertEquals(
      listOf(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.INLINE),
    )
    assertEquals(
      listOf(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.INLINE),
      FeatureTaskRuntimeReviewPassSequence.passes(CodeReviewExecutionMode.DELEGATED),
    )
  }

  @Test
  fun `auto never resolves silently and names the deciding rule`() {
    listOf(1, 2).forEach { passNumber ->
      val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, passNumber)
      assertTrue(
        resolution.decidingRule.startsWith("auto_mode_by_pass_number"),
        "Auto must report the named rule that decided the mode, got '${resolution.decidingRule}'.",
      )
    }
    assertEquals(
      "auto_mode_by_pass_number:pass_1_delegated",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 1).decidingRule,
    )
    assertEquals(
      "auto_mode_by_pass_number:pass_n_inline",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 2).decidingRule,
    )
  }

  @Test
  fun `an explicit mode overrides auto and is recorded as an override`() {
    assertEquals(
      "explicit_inline_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.INLINE, 1).decidingRule,
    )
    assertEquals(
      "explicit_delegated_override",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 1).decidingRule,
    )
    assertEquals(
      FeatureTaskRuntimeReviewPassSequence.REMEDIATION_PASS_RULE,
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.DELEGATED, 2).decidingRule,
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
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, 3)
    }
  }
}
