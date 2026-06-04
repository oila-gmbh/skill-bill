package skillbill.application.model

import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.TriageDecision

data class ReviewPreviewResult(
  val reviewRunId: String,
  val reviewSessionId: String,
  val findingCount: Int,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
)

data class ImportedReviewResult(
  val dbPath: String,
  val preview: ReviewPreviewResult,
)

data class ReviewFeedbackResult(
  val dbPath: String,
  val reviewRunId: String,
  val outcomeType: String,
  val recordedFindings: Int,
)

enum class TriageResultKind {
  LIST,
  RECORDED,
}

data class TriageResult(
  val kind: TriageResultKind,
  val dbPath: String,
  val reviewRunId: String,
  val findings: List<NumberedFinding> = emptyList(),
  val recorded: List<TriageDecision> = emptyList(),
  val telemetry: ReviewFinishedTelemetry? = null,
)

data class ReviewStatsResult(
  val dbPath: String,
  val reviewRunId: String?,
  val stats: ReviewFindingStats,
)

data class FeatureImplementStatsResult(
  val dbPath: String,
  val stats: FeatureImplementWorkflowStats,
)

data class FeatureVerifyStatsResult(
  val dbPath: String,
  val stats: FeatureVerifyWorkflowStats,
)

data class FeatureTaskRuntimeStatsResult(
  val dbPath: String,
  val stats: FeatureTaskRuntimeWorkflowStats,
)
