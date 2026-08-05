package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SKILL-159 AC-004: the reported-string surface accepts exactly the post-rename token set. An
 * unknown or pre-rename token is a typed parse failure, never a silent drop to null.
 */
class ReviewExecutionModeReportingTest {
  private fun review(executionModeLine: String?) = ReviewParser.parseReview(
    buildString {
      appendLine("Review session ID: rvs-1")
      appendLine("Review run ID: rvw-1")
      appendLine("Routed to: bill-code-review")
      executionModeLine?.let { appendLine(it) }
      appendLine()
      appendLine("### 2. Risk Register")
      appendLine("No findings.")
    },
  )

  @Test
  fun `each accepted token round trips onto the reported execution mode`() {
    listOf("inline", "delegated").forEach { token ->
      assertEquals(token, review("Execution mode: $token").executionMode)
    }
  }

  // SKILL-136 subtask 4 AC-005: a run that omits the line still records a value; with no delegation
  // evidence that value is the explicit unresolved marker, never a silent drop to inline.
  @Test
  fun `an absent execution mode line records the explicit unresolved marker`() {
    assertEquals(UNRESOLVED_ATTRIBUTION, review(null).executionMode)
  }

  @Test
  fun `an unknown or pre-rename token fails loudly instead of mapping silently`() {
    listOf("auto", "runtime", "external", "full", "light").forEach { token ->
      val failure = assertFailsWith<IllegalArgumentException> { review("Execution mode: $token") }
      assertTrue(
        failure.message.orEmpty().contains(token),
        "The rejection must name the offending token '$token', got '${failure.message}'.",
      )
    }
  }
}
