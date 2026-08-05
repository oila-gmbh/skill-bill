package skillbill.ports.persistence

import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewRunLane

/**
 * The durable completeness surface of a review run: which lanes the composed plan launched, which
 * lane produced each finding, what that attribution is worth, and the run's terminal state. It is
 * split from [ReviewRepository] because every member here is written on the terminal path for every
 * run — including a run that produced no findings — rather than as part of importing review text.
 */
interface ReviewRunCompletenessRepository {
  /** Converges a run's per-lane attribution on the given plan-sourced set; safe to re-apply. */
  fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) = Unit

  fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = emptyList()

  /**
   * Records finding-to-lane attribution from the runtime's own merge result, keyed by finding id.
   * Ingestion prefers it over provenance parsed out of review text.
   */
  fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) = Unit

  /** Pack-and-area effectiveness, grouped by canonical routed skill plus lane pack slug and area. */
  fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> = emptyList()

  /**
   * Records the durable terminal state of a run — its finish timestamp and execution mode — for
   * every run, including one that produced no findings and one imported with telemetry disabled.
   */
  fun ensureTerminalReviewState(runId: String, executionMode: String?) = Unit
}
