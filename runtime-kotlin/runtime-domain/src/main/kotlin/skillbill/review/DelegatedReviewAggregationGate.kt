package skillbill.review

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
    require(description.isNotBlank() && description.length <= 500)
  }
}

data class DelegatedReviewWorkerResult(
  val identity: DelegatedReviewAssignmentOwnership,
  val state: DelegatedReviewAggregationState,
  val findings: List<DelegatedReviewFindingEnvelope>,
) {
  init {
    require(findings.size <= 7)
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

object DelegatedReviewAggregationGate {
  fun validate(request: DelegatedReviewAggregationRequest): DelegatedReviewAggregationReceipt {
    val selectedByDigest = request.selectedAssignments.associateBy { it.assignmentDigest }
    require(selectedByDigest.size == request.selectedAssignments.size) {
      "Aggregation selection contains duplicate assignment ownership."
    }
    require(request.selectedAssignments.map { it.workerId }.distinct().size == request.selectedAssignments.size) {
      "Aggregation selection contains duplicate worker ownership."
    }
    require(request.selectedAssignments.map { it.area }.distinct().size == request.selectedAssignments.size) {
      "Aggregation selection contains duplicate declared-area ownership."
    }
    require(request.selectedAssignments.map { it.area }.toSet() == request.declaredAreas) {
      "Aggregation selection does not cover the declared review areas exactly."
    }
    require(request.workerResults.size == request.selectedAssignments.size) {
      "Aggregation requires one completed worker result for every selected assignment."
    }
    val resultDigests = request.workerResults.map { it.identity.assignmentDigest }
    require(resultDigests.distinct().size == resultDigests.size) {
      "Aggregation rejects duplicate worker ownership."
    }
    request.workerResults.forEach { result ->
      val selected = selectedByDigest[result.identity.assignmentDigest]
        ?: error("Aggregation result does not match a selected assignment.")
      require(result.state == DelegatedReviewAggregationState.COMPLETED) {
        "Aggregation rejects a worker without a completed terminal state."
      }
      require(result.identity == selected) {
        "Aggregation rejects provider, attempt, worker, or assignment identity drift."
      }
    }
    require(resultDigests.toSet() == selectedByDigest.keys) {
      "Aggregation rejects incomplete selected-assignment coverage."
    }
    return DelegatedReviewAggregationReceipt(
      assignments = request.selectedAssignments,
      findings = request.workerResults.flatMap(DelegatedReviewWorkerResult::findings),
    )
  }
}
