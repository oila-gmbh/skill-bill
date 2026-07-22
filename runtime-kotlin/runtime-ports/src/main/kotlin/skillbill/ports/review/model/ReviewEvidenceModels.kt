package skillbill.ports.review.model

import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewOperationKind

data class ReviewEvidenceRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String? = null,
  val authorizedExpansion: ReviewExpansionRecord? = null,
)

data class ReviewEvidenceBatchRequest(val lane: String, val requests: List<ReviewEvidenceRequest>) {
  init {
    require(lane.isNotBlank()) { "Evidence batch lane must not be blank." }
    require(requests.isNotEmpty()) { "Evidence batch must carry at least one request." }
    require(requests.all { it.lane == lane }) { "Every evidence request in a batch belongs to its batch lane." }
  }

  companion object {
    fun of(request: ReviewEvidenceRequest): ReviewEvidenceBatchRequest =
      ReviewEvidenceBatchRequest(request.lane, listOf(request))
  }
}

data class ReviewEvidenceResult(
  val content: String?,
  val bytes: Long,
  val cumulativeBytes: Long,
  val expansionCount: Int,
  val budgetExceeded: ReviewBudgetOutcome? = null,
  val forbidden: ForbiddenReviewOperation? = null,
)

data class ReviewEvidenceBatchResult(
  val results: List<ReviewEvidenceResult>,
  val cumulativeBytes: Long,
  val expansions: List<ReviewExpansionRecord>,
  val terminalOutcome: ReviewBudgetOutcome? = null,
)

data class ReviewToolCall(
  val lane: String,
  val kind: ReviewOperationKind,
  val target: String,
  val searchScopes: List<String> = emptyList(),
) {
  init {
    require(lane.isNotBlank() && target.isNotBlank()) { "Review tool call must carry a lane and target." }
    require(kind != ReviewOperationKind.SEARCH || searchScopes.isNotEmpty()) {
      "A review search tool call must carry explicit scopes."
    }
  }
}

data class ReviewToolCallResult(
  val forbidden: ForbiddenReviewOperation? = null,
  val budgetExceeded: ReviewBudgetOutcome? = null,
) {
  val admitted: Boolean get() = forbidden == null && budgetExceeded == null
}

data class ReviewLaneAccounting(
  val lane: String,
  val reviewId: String = "unknown",
  val packetDigest: String = "unknown",
  val assignmentDigest: String = lane,
  val launchBytes: Long = 0,
  val evidenceBytes: Long,
  val expansions: List<ReviewExpansionRecord>,
  val toolCalls: Int,
  val modelTurns: Int,
  val resultBytes: Long,
  val providerUsage: ProviderTokenUsage? = null,
  val terminalStatus: String = "completed",
  val terminalOutcome: ReviewBudgetOutcome? = null,
) {
  init {
    require(lane.isNotBlank() && reviewId.isNotBlank() && packetDigest.isNotBlank() && assignmentDigest.isNotBlank())
    require(launchBytes >= 0 && evidenceBytes >= 0 && resultBytes >= 0)
    require(toolCalls >= 0 && modelTurns >= 0)
  }
}
