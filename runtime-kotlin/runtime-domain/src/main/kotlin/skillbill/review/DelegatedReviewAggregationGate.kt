package skillbill.review

import skillbill.review.model.DelegatedReviewAggregationReceipt
import skillbill.review.model.DelegatedReviewAggregationRequest
import skillbill.review.model.DelegatedReviewAggregationState
import skillbill.review.model.DelegatedReviewWorkerResult

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
