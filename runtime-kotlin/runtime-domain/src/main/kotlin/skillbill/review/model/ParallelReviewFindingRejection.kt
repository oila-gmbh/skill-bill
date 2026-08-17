package skillbill.review.model

enum class ParallelReviewFindingRejectionReason(val wireValue: String) {
  UNRECOGNIZED_SEVERITY("unrecognized_severity"),
  INVALID_LINE_NUMBER("invalid_line_number"),
  UNPARSEABLE_STRUCTURED_PATH("unparseable_structured_path"),
  NO_ADMISSIBLE_LOCATION("no_admissible_location"),
  UNMATCHED_CANDIDATE_LINE("unmatched_candidate_line"),
}

data class ParallelReviewFindingRejection(
  val lineText: String,
  val linePosition: Int,
  val reason: ParallelReviewFindingRejectionReason,
)
