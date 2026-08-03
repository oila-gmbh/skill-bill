package skillbill.ports.persistence

import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry

interface ReviewLifecycleRepository {
  /**
   * Appends one bounded lifecycle transition. Implementations must treat event_id as an
   * idempotency key and reject a replay whose payload differs from the stored event.
   */
  fun appendReviewLifecycleEvent(event: ReviewLifecycleEvent): Boolean = false

  /** Durable lifecycle events are the recovery authority after coordinator interruption. */
  fun loadReviewLifecycleEvents(reviewId: String): List<ReviewLifecycleEvent> = emptyList()

  /** Stores the bounded worker/wave projection used for deterministic restart reconciliation. */
  fun saveDelegatedReviewLifecycle(snapshot: DelegatedReviewLifecycleSnapshot) = Unit

  fun loadDelegatedReviewLifecycle(reviewId: String): DelegatedReviewLifecycleSnapshot? = null
}

interface ReviewRepository : WorkflowStatsRepository, ReviewLifecycleRepository {
  /** Stores only the schema-bounded accounting projection; content-bearing review objects cannot cross this seam. */
  fun saveAccounting(record: ReviewAccountingRecord) = Unit

  fun loadAccounting(reviewId: String): ReviewAccountingRecord? = null

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
