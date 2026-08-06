package skillbill.ports.persistence

import skillbill.ports.persistence.model.ReviewIntegrationPassRecord
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
  fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>)

  fun fetchReviewRunLanes(runId: String): List<ReviewRunLane>

  /**
   * Records finding-to-lane attribution from the runtime's own merge result, keyed by finding id.
   * Ingestion prefers it over provenance parsed out of review text.
   */
  fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>)

  /** Pack-and-area effectiveness, grouped by canonical routed skill plus lane pack slug and area. */
  fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow>

  /**
   * Records the durable terminal state of a run — its finish timestamp and execution mode — for
   * every run, including one that produced no findings and one imported with telemetry disabled.
   */
  fun ensureTerminalReviewState(runId: String, executionMode: String?)

  /**
   * Records the integration pass's own terminal state. It is deliberately separate from lane
   * dispositions: specialist completion and integration completion are distinct durable boundaries,
   * so a crash between them must resume into the integration pass alone.
   */
  fun recordIntegrationPass(runId: String, record: ReviewIntegrationPassRecord)

  /** The durable integration state, or null when this run has not settled one yet. */
  fun fetchIntegrationPass(runId: String): ReviewIntegrationPassRecord?
}

/**
 * Every member is written on a run's terminal path, so a no-op implementation discards terminal
 * state and lane attribution with nothing to show for it. An unavailable completeness surface
 * fails loudly instead, the way [UnavailableUnaddressedFindingsRepository] does.
 */
object UnavailableReviewRunCompletenessRepository : ReviewRunCompletenessRepository {
  override fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) {
    error("Review run completeness persistence is unavailable.")
  }

  override fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> =
    error("Review run completeness persistence is unavailable.")

  override fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) {
    error("Review run completeness persistence is unavailable.")
  }

  override fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> =
    error("Review run completeness persistence is unavailable.")

  override fun ensureTerminalReviewState(runId: String, executionMode: String?) {
    error("Review run completeness persistence is unavailable.")
  }

  override fun recordIntegrationPass(runId: String, record: ReviewIntegrationPassRecord) {
    error("Review run completeness persistence is unavailable.")
  }

  override fun fetchIntegrationPass(runId: String): ReviewIntegrationPassRecord? =
    error("Review run completeness persistence is unavailable.")
}
