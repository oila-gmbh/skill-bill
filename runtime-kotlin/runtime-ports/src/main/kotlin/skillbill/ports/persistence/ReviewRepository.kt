package skillbill.ports.persistence

import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding

interface ReviewRepository {
  fun saveImportedReview(review: ImportedReview, sourcePath: String?)

  fun markOrchestrated(runId: String)

  fun updateReviewFinishedTelemetryState(runId: String, enabled: Boolean, level: String): Map<String, Any?>?

  fun recordFeedback(request: FeedbackRequest, telemetryOptions: FeedbackTelemetryOptions): Map<String, Any?>?

  fun fetchNumberedFindings(runId: String): List<NumberedFinding>

  fun findingExists(runId: String, findingId: String): Boolean

  fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome?

  fun reviewStatsPayload(runId: String?): Map<String, Any?>

  fun featureImplementStatsPayload(): Map<String, Any?>

  fun featureVerifyStatsPayload(): Map<String, Any?>
}
