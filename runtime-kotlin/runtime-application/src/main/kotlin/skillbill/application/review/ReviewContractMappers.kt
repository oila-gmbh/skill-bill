package skillbill.application.review

import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewFeedbackResult
import skillbill.application.model.ReviewPreviewResult
import skillbill.application.model.TriageResult
import skillbill.application.model.TriageResultKind
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.review.ImportedReviewContract
import skillbill.contracts.review.NumberedFindingContract
import skillbill.contracts.review.ReviewFeedbackContract
import skillbill.contracts.review.ReviewPreviewContract
import skillbill.contracts.review.TriageDecisionContract
import skillbill.contracts.review.TriageListContract
import skillbill.contracts.review.TriageRecordedContract
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.TriageDecision
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload as toPortReviewFinishedTelemetryPayload

fun ImportedReview.toReviewPreviewResult(): ReviewPreviewResult = ReviewPreviewResult(
  reviewRunId = reviewRunId,
  reviewSessionId = reviewSessionId,
  findingCount = findings.size,
  routedSkill = routedSkill,
  detectedScope = detectedScope,
  detectedStack = detectedStack,
  executionMode = executionMode,
)

fun ImportedReview.toImportedReviewResult(dbPath: String): ImportedReviewResult =
  ImportedReviewResult(dbPath = dbPath, preview = toReviewPreviewResult())

fun ReviewPreviewResult.toReviewPreviewContract(): ReviewPreviewContract = ReviewPreviewContract(
  reviewRunId = reviewRunId,
  reviewSessionId = reviewSessionId,
  findingCount = findingCount,
  routedSkill = routedSkill,
  detectedScope = detectedScope,
  detectedStack = detectedStack,
  executionMode = executionMode,
)

fun ImportedReviewResult.toImportedReviewContract(): ImportedReviewContract =
  ImportedReviewContract(dbPath = dbPath, review = preview.toReviewPreviewContract())

fun NumberedFinding.toNumberedFindingContract(): NumberedFindingContract = NumberedFindingContract(
  number = number,
  findingId = findingId,
  severity = severity,
  confidence = confidence,
  location = location,
  description = description,
)

fun TriageDecision.toTriageDecisionContract(): TriageDecisionContract = TriageDecisionContract(
  number = number,
  findingId = findingId,
  outcomeType = outcomeType,
  note = note,
)

fun ReviewFeedbackResult.toReviewFeedbackPayload(): JsonPayloadContract = ReviewFeedbackContract(
  dbPath = dbPath,
  reviewRunId = reviewRunId,
  outcomeType = outcomeType,
  recordedFindings = recordedFindings,
)

fun TriageResult.toTriagePayload(): JsonPayloadContract = when (kind) {
  TriageResultKind.LIST ->
    TriageListContract(
      dbPath = dbPath,
      reviewRunId = reviewRunId,
      findings = findings.map { finding -> finding.toNumberedFindingContract() },
    )
  TriageResultKind.RECORDED ->
    TriageRecordedContract(
      dbPath = dbPath,
      reviewRunId = reviewRunId,
      recorded = recorded.map { decision -> decision.toTriageDecisionContract() },
    )
}

fun ReviewFinishedTelemetry.toReviewFinishedTelemetryPayload(): JsonPayloadContract =
  this.toPortReviewFinishedTelemetryPayload()
