package skillbill.contracts.review

import skillbill.contracts.JsonPayloadContract
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding
import skillbill.review.TriageDecision

data class ReviewPreviewContract(
  val reviewRunId: String,
  val reviewSessionId: String,
  val findingCount: Int,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "review_run_id" to reviewRunId,
    "review_session_id" to reviewSessionId,
    "finding_count" to findingCount,
    "routed_skill" to routedSkill,
    "detected_scope" to detectedScope,
    "detected_stack" to detectedStack,
    "execution_mode" to executionMode,
  )
}

data class ImportedReviewContract(
  val dbPath: String,
  val review: ReviewPreviewContract,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>("db_path" to dbPath).apply {
    putAll(review.toPayload())
  }
}

data class ReviewFeedbackContract(
  val dbPath: String,
  val reviewRunId: String,
  val outcomeType: String,
  val recordedFindings: Int,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "db_path" to dbPath,
    "review_run_id" to reviewRunId,
    "outcome_type" to outcomeType,
    "recorded_findings" to recordedFindings,
  )
}

data class NumberedFindingContract(
  val number: Int,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "number" to number,
    "finding_id" to findingId,
    "severity" to severity,
    "confidence" to confidence,
    "location" to location,
    "description" to description,
  )
}

data class TriageDecisionContract(
  val number: Int,
  val findingId: String,
  val outcomeType: String,
  val note: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "number" to number,
    "finding_id" to findingId,
    "outcome_type" to outcomeType,
    "note" to note,
  )
}

data class TriageListContract(
  val dbPath: String,
  val reviewRunId: String,
  val findings: List<NumberedFindingContract>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "db_path" to dbPath,
    "review_run_id" to reviewRunId,
    "findings" to findings.map(NumberedFindingContract::toPayload),
  )
}

data class TriageRecordedContract(
  val dbPath: String,
  val reviewRunId: String,
  val recorded: List<TriageDecisionContract>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "db_path" to dbPath,
    "review_run_id" to reviewRunId,
    "recorded" to recorded.map(TriageDecisionContract::toPayload),
  )
}

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
