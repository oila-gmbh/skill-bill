package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeReviewPassSequenceTest {
  @Test
  fun `every pinned mode runs first as selected and second inline`() {
    CodeReviewExecutionMode.entries.forEach { pinnedMode ->
      assertEquals(
        listOf(pinnedMode, CodeReviewExecutionMode.INLINE),
        FeatureTaskRuntimeReviewPassSequence.passes(pinnedMode),
      )
    }
  }

  @Test
  fun `a pass beyond the durable cap fails loudly`() {
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      FeatureTaskRuntimeReviewPassSequence.modeForPass(CodeReviewExecutionMode.DELEGATED, 3)
    }
  }
}
