package skillbill.learnings

data class CreateLearningRequest(
  val scope: LearningScope,
  val scopeKey: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
)

data class UpdateLearningRequest(
  val learningId: Int,
  val scope: LearningScope?,
  val scopeKey: String?,
  val title: String?,
  val ruleText: String?,
  val rationale: String?,
)
