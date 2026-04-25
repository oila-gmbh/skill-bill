package skillbill.application

import skillbill.contracts.review.ImportedReviewContract
import skillbill.contracts.review.NumberedFindingContract
import skillbill.contracts.review.ReviewPreviewContract
import skillbill.contracts.review.TriageDecisionContract
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding
import skillbill.review.TriageDecision

fun ImportedReview.toReviewPreviewContract(): ReviewPreviewContract = ReviewPreviewContract(
  reviewRunId = reviewRunId,
  reviewSessionId = reviewSessionId,
  findingCount = findings.size,
  routedSkill = routedSkill,
  detectedScope = detectedScope,
  detectedStack = detectedStack,
  executionMode = executionMode,
)

fun ImportedReview.toImportedReviewContract(dbPath: String): ImportedReviewContract =
  ImportedReviewContract(dbPath = dbPath, review = toReviewPreviewContract())

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
