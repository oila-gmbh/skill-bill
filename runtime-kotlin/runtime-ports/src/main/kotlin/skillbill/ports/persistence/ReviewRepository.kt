package skillbill.ports.persistence

import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry

interface ReviewRepository : WorkflowStatsRepository {
  fun saveImportedReview(review: ImportedReview, sourcePath: String?)

  fun markOrchestrated(runId: String)

  fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
  ): ReviewFinishedTelemetry?

  fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
  ): ReviewFinishedTelemetry?

  fun fetchNumberedFindings(runId: String): List<NumberedFinding>

  fun findingExists(runId: String, findingId: String): Boolean

  fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome?

  fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot
}
