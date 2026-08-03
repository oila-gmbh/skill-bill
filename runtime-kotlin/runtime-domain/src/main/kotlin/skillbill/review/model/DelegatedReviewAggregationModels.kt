package skillbill.review.model

private const val MAX_DELEGATED_FINDING_DESCRIPTION_CHARS = 500
private const val MAX_DELEGATED_FINDINGS_PER_WORKER = 7

data class DelegatedReviewAssignmentOwnership(
  val workerId: String,
  val providerId: String,
  val attempt: Int,
  val assignmentDigest: String,
  val area: String,
) {
  init {
    require(workerId.isNotBlank() && providerId.isNotBlank() && area.isNotBlank())
    require(attempt >= 1)
    require(assignmentDigest.matches(Regex("^[a-f0-9]{64}$")))
  }
}

data class DelegatedReviewFindingEnvelope(
  val severity: String,
  val confidence: String,
  val repositoryPath: String,
  val line: Int,
  val description: String,
) {
  init {
    require(severity.lowercase() in setOf("blocker", "major", "minor", "nit"))
    require(confidence in setOf("High", "Medium", "Low"))
    require(repositoryPath.isNotBlank() && !repositoryPath.startsWith("../") && "/../" !in repositoryPath)
    require(line >= 1)
    require(
      description.isNotBlank() &&
        description.length <= MAX_DELEGATED_FINDING_DESCRIPTION_CHARS,
    )
  }
}

data class DelegatedReviewWorkerResult(
  val identity: DelegatedReviewAssignmentOwnership,
  val state: DelegatedReviewAggregationState,
  val findings: List<DelegatedReviewFindingEnvelope>,
) {
  init {
    require(findings.size <= MAX_DELEGATED_FINDINGS_PER_WORKER)
  }
}

enum class DelegatedReviewAggregationState { COMPLETED, FAILED, TIMED_OUT, CANCELLED, AGGREGATED }

data class DelegatedReviewAggregationRequest(
  val selectedAssignments: List<DelegatedReviewAssignmentOwnership>,
  val declaredAreas: Set<String>,
  val workerResults: List<DelegatedReviewWorkerResult>,
) {
  init {
    require(selectedAssignments.isNotEmpty())
    require(declaredAreas.isNotEmpty())
  }
}

data class DelegatedReviewAggregationReceipt(
  val assignments: List<DelegatedReviewAssignmentOwnership>,
  val findings: List<DelegatedReviewFindingEnvelope>,
)
