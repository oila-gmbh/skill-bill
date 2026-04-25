package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.infrastructure.sqlite.SQLiteLearningStore
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.TriageRuntime
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningSourceValidation
import skillbill.learnings.RejectedLearningSourceOutcome
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSummaryPayload
import skillbill.learnings.scopeCounts
import skillbill.tempDbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TriageAndLearningsRuntimeTest {
  @Test
  fun `parseTriageDecisions expands structured selections and normalizes actions`() {
    val decisions =
      TriageDecisionParser.parseTriageDecisions(
        rawDecisions = listOf("fix=[1] reject=[2]"),
        numberedFindings =
        listOf(
          NumberedFinding(1, "F-001", "Major", "High", "README.md:12", "one"),
          NumberedFinding(2, "F-002", "Minor", "Medium", "install.sh:88", "two"),
        ),
      )

    assertEquals(2, decisions.size)
    assertEquals("fix_applied", decisions[0].outcomeType)
    assertEquals("fix_rejected", decisions[1].outcomeType)
  }

  @Test
  fun `recordFeedback and learnings resolve preserve rejected-source and scope ordering`() {
    val (_, connection) = tempDbConnection("triage-learnings")
    connection.use {
      val review = importSampleReview(connection)
      val telemetryPayload = rejectFinding(connection, review.reviewRunId, "F-002", "Keep the current prompt wording.")
      assertEquals("rvs-20260402-001", telemetryPayload?.get("review_session_id"))

      val globalId = addLearning(connection, review.reviewRunId, LearningScope.GLOBAL, "", "Prefer explicit wording")
      val repoId = addLearning(connection, review.reviewRunId, LearningScope.REPO, "skill-bill", "Repo phrasing")
      val skillId =
        addLearning(
          connection,
          review.reviewRunId,
          LearningScope.SKILL,
          "bill-kotlin-code-review",
          "Review skill phrasing",
        )

      val (_, _, resolved) =
        SQLiteLearningStore.resolveLearnings(
          connection = connection,
          repoScopeKey = "skill-bill",
          skillName = "bill-kotlin-code-review",
        )

      assertEquals(listOf(skillId, repoId, globalId), resolved.map { it.id })
      assertTrue(resolved.first().rationale.contains("current prompt wording"))

      val payloadEntries = resolved.map(::learningPayload)
      saveCachedLearnings(connection, review.reviewSessionId, payloadEntries)

      val cached = SQLiteLearningStore.fetchSessionLearnings(connection, review.reviewSessionId)
      assertNotNull(cached)
      assertEquals(3, (cached["applied_learning_count"] as Number).toInt())
    }
  }
}

private fun importSampleReview(connection: java.sql.Connection): ImportedReview {
  val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
  ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
  return review
}

private fun rejectFinding(
  connection: java.sql.Connection,
  reviewRunId: String,
  findingId: String,
  note: String,
): Map<String, Any?>? = TriageRuntime.recordFeedback(
  connection = connection,
  request =
  FeedbackRequest(
    reviewRunId = reviewRunId,
    findingIds = listOf(findingId),
    eventType = "fix_rejected",
    note = note,
  ),
  telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
)

private fun addLearning(
  connection: java.sql.Connection,
  reviewRunId: String,
  scope: LearningScope,
  scopeKey: String,
  title: String,
): Int = SQLiteLearningStore.addLearning(
  connection = connection,
  request =
  CreateLearningRequest(
    scope = scope,
    scopeKey = scopeKey,
    title = title,
    ruleText = "Rule text for $title.",
    rationale = "",
    sourceReviewRunId = reviewRunId,
    sourceFindingId = "F-002",
  ),
  sourceValidation =
  LearningSourceValidation(
    reviewRunId = reviewRunId,
    findingId = "F-002",
    rejectedOutcome = RejectedLearningSourceOutcome("fix_rejected", "Keep the current prompt wording."),
  ),
)

private fun saveCachedLearnings(
  connection: java.sql.Connection,
  reviewSessionId: String,
  payloadEntries: List<Map<String, Any?>>,
) {
  SQLiteLearningStore.saveSessionLearnings(
    connection = connection,
    reviewSessionId = reviewSessionId,
    learningsJson =
    JsonSupport.mapToJsonString(
      mapOf(
        "applied_learning_count" to payloadEntries.size,
        "applied_learning_references" to payloadEntries.map { it["reference"] },
        "applied_learnings" to payloadEntries.joinToString(", ") { it["reference"].toString() },
        "scope_counts" to scopeCounts(payloadEntries),
        "learnings" to payloadEntries.map(::learningSummaryPayload),
      ),
    ),
  )
}
