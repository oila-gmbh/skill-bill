package skillbill.learnings

data class LearningRecord(
  val id: Int,
  val scope: String,
  val scopeKey: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val status: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
  val createdAt: String,
  val updatedAt: String,
)
