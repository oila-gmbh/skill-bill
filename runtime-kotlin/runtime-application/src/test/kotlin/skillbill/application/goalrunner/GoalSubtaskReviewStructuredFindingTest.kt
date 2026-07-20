package skillbill.application.goalrunner

import kotlin.test.Test
import kotlin.test.assertEquals

class GoalSubtaskReviewStructuredFindingTest {
  @Test
  fun `canonical review envelope preserves ledger category and location`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "major",
            "message" to "Writer-produced finding",
            "issue_category" to "behavior_correctness",
            "location" to "src/Feature.kt:42",
          ),
        ),
      ),
    )

    assertEquals(
      StructuredGoalReviewFinding(
        severity = "major",
        message = "Writer-produced finding",
        issueCategory = "behavior_correctness",
        location = "src/Feature.kt:42",
        compactLabel = "Review",
      ),
      GoalSubtaskReviewSummaryReducer.structuredFindings(output).single(),
    )
  }
}
