package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningStore
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSummaryPayload
import skillbill.tempDbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewStatsRuntimeTest {
  @Test
  fun `statsPayload summarizes latest outcomes`() {
    val (_, connection) = tempDbConnection("review-stats")
    connection.use {
      val review = importReviewedSample(connection)

      val statsPayload = ReviewStatsRuntime.statsPayload(connection, review.reviewRunId)
      assertEquals(2, statsPayload["total_findings"])
      assertEquals(1, statsPayload["accepted_findings"])
      assertEquals(1, statsPayload["rejected_findings"])
    }
  }

  @Test
  fun `review-finished payload includes cached learnings and full finding details`() {
    val (_, connection) = tempDbConnection("review-finished-payload")
    connection.use {
      val review = importReviewedSample(connection)
      cacheSkillLearning(connection, review.reviewRunId, review.reviewSessionId)

      val anonymousPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "anonymous",
        )
      val fullPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "full",
        )

      val anonymousLearnings = anonymousPayload["learnings"] as Map<*, *>
      assertEquals(1, anonymousLearnings["applied_count"])
      assertEquals("L-001", anonymousLearnings["applied_summary"])
      assertEquals("bill-kotlin-code-review", anonymousPayload["routed_skill"])
      assertEquals("unstaged changes", anonymousPayload["review_scope"])
      assertTrue((anonymousPayload["accepted_finding_details"] as List<*>).isNotEmpty())
      val rejectedFindingDetails =
        (fullPayload["rejected_finding_details"] as? List<*>)
          ?.filterIsInstance<Map<String, Any?>>()
      assertNotNull(rejectedFindingDetails?.first()?.get("description"))
    }
  }

  @Test
  fun `feature implement stats payload aggregates persisted session rows`() {
    val (_, connection) = tempDbConnection("workflow-stats")
    connection.use {
      insertFeatureImplementSession(connection)
      insertFeatureVerifySession(connection)

      val implementPayload = ReviewStatsRuntime.featureImplementStatsPayload(connection)

      assertEquals(1, implementPayload["total_runs"])
      assertEquals(1, (implementPayload["feature_size_counts"] as Map<*, *>)["MEDIUM"])
    }
  }

  @Test
  fun `feature verify stats payload aggregates persisted session rows`() {
    val (_, connection) = tempDbConnection("workflow-verify-stats")
    connection.use {
      insertFeatureVerifySession(connection)

      val verifyPayload = ReviewStatsRuntime.featureVerifyStatsPayload(connection)

      assertEquals(1, verifyPayload["total_runs"])
      assertEquals(1, verifyPayload["runs_with_gaps_found"])
      assertEquals(1, (verifyPayload["history_relevance_counts"] as Map<*, *>)["medium"])
    }
  }
}

private fun importReviewedSample(connection: java.sql.Connection): ImportedReview {
  val review = ReviewRuntime.parseReview(SAMPLE_REVIEW.trimIndent())
  ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
  recordFindingOutcome(connection, review.reviewRunId, "F-001", "finding_accepted", "")
  recordFindingOutcome(connection, review.reviewRunId, "F-002", "fix_rejected", "Intentional wording")
  return review
}

private fun recordFindingOutcome(
  connection: java.sql.Connection,
  reviewRunId: String,
  findingId: String,
  eventType: String,
  note: String,
) {
  TriageRuntime.recordFeedback(
    connection = connection,
    request =
    FeedbackRequest(
      reviewRunId = reviewRunId,
      findingIds = listOf(findingId),
      eventType = eventType,
      note = note,
    ),
    telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
  )
}

private fun cacheSkillLearning(connection: java.sql.Connection, reviewRunId: String, reviewSessionId: String) {
  val learningId =
    LearningStore.addLearning(
      connection = connection,
      request =
      CreateLearningRequest(
        scope = LearningScope.SKILL,
        scopeKey = "bill-kotlin-code-review",
        title = "Match wording",
        ruleText = "Keep wording aligned with routed skill output.",
        rationale = "",
        sourceReviewRunId = reviewRunId,
        sourceFindingId = "F-002",
      ),
    )
  val learningPayload = learningPayload(LearningStore.getLearning(connection, learningId))
  LearningsRuntime.saveSessionLearnings(
    connection = connection,
    reviewSessionId = reviewSessionId,
    learningsJson =
    JsonSupport.mapToJsonString(
      mapOf(
        "applied_learning_count" to 1,
        "applied_learning_references" to listOf(learningPayload["reference"]),
        "applied_learnings" to learningPayload["reference"],
        "scope_counts" to mapOf("global" to 0, "repo" to 0, "skill" to 1),
        "learnings" to listOf(learningSummaryPayload(learningPayload)),
      ),
    ),
  )
}

private fun insertFeatureImplementSession(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_implement_sessions (
        session_id,
        feature_size,
        rollout_needed,
        acceptance_criteria_count,
        spec_word_count,
        completion_status,
        feature_flag_used,
        feature_flag_pattern,
        files_created,
        files_modified,
        tasks_completed,
        review_iterations,
        audit_result,
        audit_iterations,
        validation_result,
        boundary_history_written,
        boundary_history_value,
        pr_created,
        started_at,
        finished_at
      ) VALUES (
        'fis-1',
        'MEDIUM',
        1,
        3,
        200,
        'completed',
        0,
        'none',
        2,
        4,
        5,
        1,
        'all_pass',
        1,
        'pass',
        0,
        'low',
        1,
        '2026-04-23 10:00:00',
        '2026-04-23 10:10:00'
      )
      """.trimIndent(),
    )
  }
}

private fun insertFeatureVerifySession(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_verify_sessions (
        session_id,
        acceptance_criteria_count,
        rollout_relevant,
        spec_summary,
        feature_flag_audit_performed,
        review_iterations,
        audit_result,
        completion_status,
        gaps_found,
        history_relevance,
        history_helpfulness,
        started_at,
        finished_at
      ) VALUES (
        'fvr-1',
        2,
        1,
        'Verify review domain.',
        1,
        2,
        'all_pass',
        'completed',
        '["gap"]',
        'medium',
        'high',
        '2026-04-23 10:00:00',
        '2026-04-23 10:05:00'
      )
      """.trimIndent(),
    )
  }
}
