package skillbill.learnings.model

data class RejectedLearningSourceOutcome(
  val eventType: String,
  val note: String,
)

data class LearningSourceReference(
  val reviewRunId: String,
  val findingId: String,
)

data class LearningSourceValidation(
  val reviewRunId: String,
  val findingId: String,
  val rejectedOutcome: RejectedLearningSourceOutcome,
)
