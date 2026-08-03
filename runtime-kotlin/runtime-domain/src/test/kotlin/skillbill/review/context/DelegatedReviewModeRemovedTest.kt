package skillbill.review.context

import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * AC-003: the removed delegated subsystem must fail loudly at the resolution seam. A silent
 * downgrade to inline would hand an operator reduced coverage under the name they did not ask for.
 */
class DelegatedReviewModeRemovedTest {
  @Test
  fun `delegated resolution throws the typed removal error instead of falling back to inline`() {
    val failure = assertFailsWith<DelegatedReviewModeRemovedException> {
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.DELEGATED)
    }

    assertContains(failure.message.orEmpty(), "External delegated code review was removed")
  }

  @Test
  fun `delegated resolution with a pass number also throws rather than resolving a depth`() {
    assertFailsWith<DelegatedReviewModeRemovedException> {
      ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.DELEGATED, reviewPassNumber = 1)
    }
  }

  @Test
  fun `the error names the surviving inline and parallel-review alternatives`() {
    val failure = assertFailsWith<DelegatedReviewModeRemovedException> {
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.DELEGATED)
    }

    val message = failure.message.orEmpty()
    assertContains(message, "code-review:inline")
    assertContains(message, "parallel-review:<agent>")
  }
}
