package skillbill.ports.persistence

import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewRunLane

interface ReviewRepository : WorkflowStatsRepository {
  /** Stores only the schema-bounded accounting projection; content-bearing review objects cannot cross this seam. */
  fun saveAccounting(record: ReviewAccountingRecord) = Unit

  fun loadAccounting(reviewId: String): ReviewAccountingRecord? = null

  fun saveImportedReview(review: ImportedReview, sourcePath: String?)

  /** Converges a run's per-lane attribution on the given plan-sourced set; safe to re-apply. */
  fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) = Unit

  fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = emptyList()

  /** Pack-and-area effectiveness, grouped by canonical routed skill plus lane pack slug and area. */
  fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> = emptyList()

  /**
   * Records the durable terminal state of a run — its finish timestamp and execution mode — for
   * every run, including one that produced no findings and one imported with telemetry disabled.
   */
  fun ensureTerminalReviewState(runId: String, executionMode: String?) = Unit

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
