package skillbill.review.model

data class ParallelReviewParseResult(
  val findings: List<ParallelReviewRawFinding> = emptyList(),
  val rejections: List<ParallelReviewFindingRejection> = emptyList(),
  val candidateCount: Int = 0,
)
