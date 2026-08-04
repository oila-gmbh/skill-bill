package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeReviewPassSequenceTest {
  private val remediationPasses = listOf(2, 3, 5, 10, 47)

  @Test
  fun `pass one keeps the tier its pinned mode resolves to`() {
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.AUTO, 1),
    )
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, 1),
    )
    assertEquals(
      CodeReviewExecutionMode.DELEGATED,
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.DELEGATED, 1),
    )
  }

  @Test
  fun `every remediation pass runs inline under the remediation rule for any pinned mode`() {
    CodeReviewExecutionMode.entries.forEach { pinnedMode ->
      remediationPasses.forEach { passNumber ->
        val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pinnedMode, passNumber)
        assertEquals(
          CodeReviewExecutionMode.INLINE,
          resolution.resolvedTier,
          "Pass $passNumber under '$pinnedMode' must review only the remediation delta inline.",
        )
        assertEquals(
          FeatureTaskRuntimeReviewPassSequence.REMEDIATION_PASS_RULE,
          resolution.decidingRule,
        )
      }
    }
  }

  @Test
  fun `auto never resolves silently and names the deciding rule`() {
    assertEquals(
      "auto_mode_by_pass_number:pass_1_inline",
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(CodeReviewExecutionMode.AUTO, 1).decidingRule,
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
  fun `a non-positive pass number fails loudly`() {
    listOf(0, -1, -7).forEach { passNumber ->
      assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
        FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.INLINE, passNumber)
      }
    }
  }
}
