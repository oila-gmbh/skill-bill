package skillbill.review

data class ImportedFinding(
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
  val findingText: String,
)

data class ImportedReview(
  val reviewRunId: String,
  val reviewSessionId: String,
  val rawText: String,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
  val specialistReviews: List<String>,
  val findings: List<ImportedFinding>,
)

data class ReviewSummary(
  val reviewRunId: String,
  val reviewSessionId: String?,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
  val specialistReviewsRaw: String?,
  val reviewFinishedAt: String?,
  val reviewFinishedEventEmittedAt: String?,
  val orchestratedRun: Boolean,
)

data class FindingMetadata(
  val findingId: String,
  val severity: String,
  val confidence: String,
)

data class NumberedFinding(
  val number: Int,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
)

data class TriageDecision(
  val number: Int,
  val findingId: String,
  val outcomeType: String,
  val note: String,
)

data class FeedbackRequest(
  val reviewRunId: String,
  val findingIds: List<String>,
  val eventType: String,
  val note: String,
)

data class FeedbackTelemetryOptions(
  val enabled: Boolean? = null,
  val level: String? = null,
)

data class FindingOutcomeRow(
  val reviewRunId: String,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
  val outcomeType: String,
  val note: String,
)
