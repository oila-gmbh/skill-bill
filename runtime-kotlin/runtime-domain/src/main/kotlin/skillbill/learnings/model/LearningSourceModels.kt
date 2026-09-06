package skillbill.learnings.model

import skillbill.review.model.ReviewRunId

data class RejectedLearningSourceOutcome(
  val eventType: String,
  val note: String,
)

data class LearningSourceReference(
  val reviewRunId: ReviewRunId,
  val findingId: String,
)

data class LearningSourceValidation(
  val reviewRunId: ReviewRunId,
  val findingId: String,
  val rejectedOutcome: RejectedLearningSourceOutcome,
)
