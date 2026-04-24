package skillbill.ports.persistence

import skillbill.review.FeedbackRequest
import skillbill.review.FeedbackTelemetryOptions
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding

interface ReviewRepository {
  fun saveImportedReview(review: ImportedReview, sourcePath: String?)

  fun markOrchestrated(runId: String)

  fun updateReviewFinishedTelemetryState(runId: String, enabled: Boolean, level: String): Map<String, Any?>?

  fun recordFeedback(request: FeedbackRequest, telemetryOptions: FeedbackTelemetryOptions): Map<String, Any?>?

  fun fetchNumberedFindings(runId: String): List<NumberedFinding>

  fun reviewStatsPayload(runId: String?): Map<String, Any?>

  fun featureImplementStatsPayload(): Map<String, Any?>

  fun featureVerifyStatsPayload(): Map<String, Any?>
}
