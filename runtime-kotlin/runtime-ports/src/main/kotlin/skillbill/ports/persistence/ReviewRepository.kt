package skillbill.ports.persistence

import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry

interface ReviewRepository {
  fun saveImportedReview(review: ImportedReview, sourcePath: String?)

  fun markOrchestrated(runId: String)

  fun updateReviewFinishedTelemetryState(runId: String, enabled: Boolean, level: String): ReviewFinishedTelemetry?

  fun recordFeedback(request: FeedbackRequest, telemetryOptions: FeedbackTelemetryOptions): ReviewFinishedTelemetry?

  fun fetchNumberedFindings(runId: String): List<NumberedFinding>

  fun findingExists(runId: String, findingId: String): Boolean

  fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome?

  fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot

  fun featureImplementStats(): FeatureImplementWorkflowStats

  fun featureVerifyStats(): FeatureVerifyWorkflowStats
}
