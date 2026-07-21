package skillbill.application.goalrunner

import skillbill.goalrunner.model.UNADDRESSED_FINDING_CATEGORIES
import skillbill.goalrunner.model.UNADDRESSED_FINDING_SEVERITIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

  @Test
  fun `ledger findings keep an in-vocabulary issue category`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "major",
            "message" to "Writer-produced finding",
            "issue_category" to "security",
            "location" to "src/Feature.kt:42",
          ),
          mapOf(
            "severity" to "minor",
            "message" to "Second finding",
            "issue_category" to "data_persistence",
            "location" to "src/Other.kt:7",
          ),
        ),
      ),
    )

    val ledgerFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = output,
      issueKey = "SKILL-135",
      subtaskId = 3,
      workflowId = "workflow-1",
      reviewPassNumber = 1,
    )

    assertEquals(listOf("other", "data_persistence"), ledgerFindings.map { it.issueCategory })
    assertTrue(ledgerFindings.all { it.issueCategory in UNADDRESSED_FINDING_CATEGORIES })
  }

  @Test
  fun `ledger findings keep an in-vocabulary severity`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "critical",
            "message" to "Agent-invented severity",
            "issue_category" to "security",
            "location" to "src/Feature.kt:42",
          ),
          mapOf(
            "severity" to "blocker",
            "message" to "Governed severity",
            "issue_category" to "security",
            "location" to "src/Other.kt:7",
          ),
        ),
      ),
    )

    val ledgerFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = output,
      issueKey = "SKILL-135",
      subtaskId = 3,
      workflowId = "workflow-1",
      reviewPassNumber = 1,
    )

    assertEquals(listOf("nit", "blocker"), ledgerFindings.map { it.severity })
    assertTrue(ledgerFindings.all { it.severity in UNADDRESSED_FINDING_SEVERITIES })
  }
}
