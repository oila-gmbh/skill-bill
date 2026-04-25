package skillbill.infrastructure.sqlite.review

import skillbill.review.ImportedFinding
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding
import skillbill.review.ReviewSummary
import java.sql.Connection

fun existingReviewSummary(connection: Connection, reviewRunId: String): ReviewSummary? =
  connection.prepareStatement(reviewSummarySql).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) resultSet.toReviewSummary() else null
    }
  }

fun reviewSummaryChanged(
  existingReviewSummary: ReviewSummary?,
  review: ImportedReview,
  existingFindings: List<ImportedFinding>,
): Boolean = existingReviewSummary == null ||
  existingReviewSummary.reviewSessionId != review.reviewSessionId ||
  existingReviewSummary.routedSkill != review.routedSkill ||
  existingReviewSummary.detectedScope != review.detectedScope ||
  existingReviewSummary.detectedStack != review.detectedStack ||
  existingReviewSummary.executionMode != review.executionMode ||
  existingReviewSummary.specialistReviewsRaw != review.specialistReviews.joinToString(",") ||
  existingFindings != review.findings

fun upsertReviewRun(connection: Connection, review: ImportedReview, sourcePath: String?) {
  connection.prepareStatement(
    """
    INSERT INTO review_runs (
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      specialist_reviews,
      source_path,
      raw_text
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id) DO UPDATE SET
      review_session_id = excluded.review_session_id,
      routed_skill = excluded.routed_skill,
      detected_scope = excluded.detected_scope,
      detected_stack = excluded.detected_stack,
      execution_mode = excluded.execution_mode,
      specialist_reviews = excluded.specialist_reviews,
      source_path = excluded.source_path,
      raw_text = excluded.raw_text
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.setString(PARAM_TWO, review.reviewSessionId)
    statement.setString(PARAM_THREE, review.routedSkill)
    statement.setString(PARAM_FOUR, review.detectedScope)
    statement.setString(PARAM_FIVE, review.detectedStack)
    statement.setString(PARAM_SIX, review.executionMode)
    statement.setString(PARAM_SEVEN, review.specialistReviews.joinToString(","))
    statement.setString(PARAM_EIGHT, sourcePath)
    statement.setString(PARAM_NINE, review.rawText)
    statement.executeUpdate()
  }
}

fun replaceFindings(connection: Connection, review: ImportedReview) {
  connection.prepareStatement("DELETE FROM findings WHERE review_run_id = ?").use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.executeUpdate()
  }
  review.findings.forEach { finding ->
    connection.prepareStatement(
      """
      INSERT INTO findings (
        review_run_id,
        finding_id,
        severity,
        confidence,
        location,
        description,
        finding_text
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, review.reviewRunId)
      statement.setString(PARAM_TWO, finding.findingId)
      statement.setString(PARAM_THREE, finding.severity)
      statement.setString(PARAM_FOUR, finding.confidence)
      statement.setString(PARAM_FIVE, finding.location)
      statement.setString(PARAM_SIX, finding.description)
      statement.setString(PARAM_SEVEN, finding.findingText)
      statement.executeUpdate()
    }
  }
}

fun java.sql.ResultSet.toImportedFinding(): ImportedFinding = ImportedFinding(
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  location = getString("location"),
  description = getString("description"),
  findingText = getString("finding_text"),
)

fun java.sql.ResultSet.toReviewSummary(): ReviewSummary = ReviewSummary(
  reviewRunId = getString("review_run_id"),
  reviewSessionId = getString("review_session_id"),
  routedSkill = getString("routed_skill"),
  detectedScope = getString("detected_scope"),
  detectedStack = getString("detected_stack"),
  executionMode = getString("execution_mode"),
  specialistReviewsRaw = getString("specialist_reviews"),
  reviewFinishedAt = getString("review_finished_at"),
  reviewFinishedEventEmittedAt = getString("review_finished_event_emitted_at"),
  orchestratedRun = getBoolean("orchestrated_run"),
)

fun java.sql.ResultSet.toNumberedFinding(number: Int): NumberedFinding = NumberedFinding(
  number = number,
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  location = getString("location"),
  description = getString("description"),
)
