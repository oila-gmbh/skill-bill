package skillbill.application

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-010 and AC-011: the reserved remediation pass emits one evidenced verdict per prior
 * Blocker, and an unevidenced disposition is rejected at the parse seam.
 */
class GoalSubtaskBlockerDispositionParseTest {
  private fun output(vararg dispositions: Map<String, Any?>): Map<String, Any?> =
    mapOf("produced_outputs" to mapOf("blocker_dispositions" to dispositions.toList()))

  @Test
  fun `an evidenced disposition parses one verdict per prior blocker`() {
    val parsed = GoalSubtaskReviewSummaryReducer.blockerDispositions(
      output(
        mapOf("finding_id" to "F-001", "verdict" to "resolved", "evidence" to listOf("guard added at the write seam")),
        mapOf("finding_id" to "F-002", "verdict" to "superseded", "evidence" to listOf("call site deleted")),
      ),
    )
    assertEquals(listOf("F-001", "F-002"), parsed.map { it.findingId })
    assertEquals(GoalSubtaskBlockerDispositionVerdict.RESOLVED, parsed.first().verdict)
    assertEquals(GoalSubtaskBlockerDispositionVerdict.SUPERSEDED, parsed.last().verdict)
  }

  @Test
  fun `an unevidenced disposition is rejected at the parse seam`() {
    val error = assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(mapOf("finding_id" to "F-001", "verdict" to "resolved")),
      )
    }
    assertTrue(error.message.orEmpty().contains("evidence"))

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(mapOf("finding_id" to "F-001", "verdict" to "resolved", "evidence" to listOf("   "))),
      )
    }
  }

  @Test
  fun `an unknown verdict is rejected rather than coerced`() {
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewSummaryReducer.blockerDispositions(
        output(mapOf("finding_id" to "F-001", "verdict" to "probably_fine", "evidence" to listOf("line 3"))),
      )
    }
  }

  @Test
  fun `a pass with no dispositions parses empty rather than failing`() {
    assertEquals(emptyList(), GoalSubtaskReviewSummaryReducer.blockerDispositions(emptyMap()))
  }
}
