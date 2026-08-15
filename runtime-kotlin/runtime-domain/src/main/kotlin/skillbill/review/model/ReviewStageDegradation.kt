package skillbill.review.model

enum class ReviewStageDegradationReason(val wireValue: String) {
  SPEC_CONTEXT_NONE("spec_context_none"),
  ADJUDICATION_SKIPPED("adjudication_skipped"),
  WORKER_LAUNCH_OR_RETURN_FAILED("worker_launch_or_return_failed"),
  STAGE_BOUNDARY_UNREACHED("stage_boundary_unreached"),
}

data class ReviewStageDegradationMeasurement(
  val reviewRunId: String,
  val seam: String,
  val expected: String,
  val actual: String,
  val reason: ReviewStageDegradationReason,
) {
  init {
    require(reviewRunId.isNotBlank()) { "Review stage degradation review_run_id must not be blank." }
    require(seam.isNotBlank()) { "Review stage degradation seam must not be blank." }
    require(expected.isNotBlank()) { "Review stage degradation expected must not be blank." }
    require(actual.isNotBlank()) { "Review stage degradation actual must not be blank." }
  }
}

const val REVIEW_STAGE_DEGRADATION_EVENT_NAME: String = "skillbill_review_stage_degradation"
const val REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION: String = "1.9.0"
const val REVIEW_FINISHED_LEGACY_CONTRACT_VERSION: String = "1.8.0"
const val REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME: String = "skillbill_review_finished_legacy_regenerated"
