package skillbill.review.model

enum class ParallelReviewSeverity(val displayName: String) {
  BLOCKER("Blocker"),
  MAJOR("Major"),
  MINOR("Minor"),
  NIT("Nit"),
}

data class ParallelReviewRawFinding(
  val severity: ParallelReviewSeverity,
  val confidence: String,
  val location: String,
  val description: String,
)

data class ParallelReviewLaneResult(
  val agentId: String,
  val findings: List<ParallelReviewRawFinding>,
  val rawOutput: String,
)

data class ParallelReviewMergedFinding(
  val fNumber: String,
  val agentIds: List<String>,
  val severity: ParallelReviewSeverity,
  val confidence: String,
  val location: String,
  val description: String,
)

data class ParallelReviewMergeResult(
  val findings: List<ParallelReviewMergedFinding>,
  val formattedOutput: String,
)
