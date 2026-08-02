package skillbill.ports.review.model

import skillbill.contracts.review.REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewOperationKind
private const val REVIEW_LIFECYCLE_MAX_TEXT_CHARS: Int = 500
private const val REVIEW_LIFECYCLE_MAX_DIAGNOSTIC_CHARS: Int = 200
private const val REVIEW_LIFECYCLE_MAX_IDENTIFIER_CHARS: Int = 200
private const val REVIEW_LIFECYCLE_MAX_TIMESTAMP_CHARS: Int = 64
private val REVIEW_LIFECYCLE_TIMESTAMP = Regex("^[0-9T:.+Z-]+$")

private fun requireLifecycleTimestamp(value: String) {
  require(value.isNotBlank() && value.length <= REVIEW_LIFECYCLE_MAX_TIMESTAMP_CHARS)
  require(REVIEW_LIFECYCLE_TIMESTAMP.matches(value))
}

private fun requireLifecycleIdentifier(value: String) {
  require(value.isNotBlank() && value.length <= REVIEW_LIFECYCLE_MAX_IDENTIFIER_CHARS)
}

enum class ReviewLifecycleComponent { COORDINATOR, WORKER, AGGREGATION, TERMINAL }

enum class ReviewLifecycleEventKind {
  COORDINATOR_PREPARED,
  WORKER_SELECTED,
  WORKER_QUEUED,
  WORKER_LAUNCHED,
  WORKER_RUNNING,
  WORKER_PROGRESS,
  WORKER_COMPLETED,
  WORKER_FAILED,
  WORKER_TIMED_OUT,
  WORKER_CANCELLED,
  WORKER_UNAVAILABLE,
  WORKER_INVALID_OUTPUT,
  AGGREGATION_STARTED,
  AGGREGATION_COMPLETED,
  AGGREGATION_FAILED,
  TERMINAL_COMPLETED,
  TERMINAL_FAILED,
  TERMINAL_TIMED_OUT,
  TERMINAL_CANCELLED,
  COORDINATOR_CRASHED,
}

enum class ReviewWorkerLifecycleState {
  SELECTED,
  QUEUED,
  LAUNCHED,
  RUNNING,
  COMPLETED,
  FAILED,
  TIMED_OUT,
  CANCELLED,
  UNAVAILABLE,
  INVALID_OUTPUT,
}

enum class ReviewProcessOutcome {
  NOT_STARTED,
  ZERO_EXIT,
  NON_ZERO_EXIT,
  INTERRUPTED,
  TIMED_OUT,
  UNAVAILABLE,
  INVALID_OUTPUT,
  AGGREGATION_FAILURE,
  MISSING_RESULT,
  COORDINATOR_CRASH,
}

data class ReviewLivenessObservation(
  val kind: Kind,
  val observedAt: String,
  val status: String,
) {
  enum class Kind { PROCESS_HEARTBEAT, MCP_HEARTBEAT }

  init {
    requireLifecycleTimestamp(observedAt)
    require(status.isNotBlank())
    require(status.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
  }
}

data class ReviewProviderOutputObservation(
  val observedAt: String,
  val outcome: String,
  val byteSize: Long,
  val sha256: String,
) {
  init {
    requireLifecycleTimestamp(observedAt)
    require(outcome.isNotBlank())
    require(outcome.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
    require(byteSize >= 0 && sha256.matches(Regex("^[a-f0-9]{64}$")))
  }
}

data class ReviewDeclaredSpecialistProgress(
  val observedAt: String,
  val progressId: String,
  val label: String,
) {
  init {
    requireLifecycleTimestamp(observedAt)
    requireLifecycleIdentifier(progressId)
    require(label.isNotBlank())
    require(progressId.length <= REVIEW_LIFECYCLE_MAX_DIAGNOSTIC_CHARS)
    require(label.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
  }
}

data class ReviewDurableWorkerProgress(
  val observedAt: String,
  val progressId: String,
  val label: String,
) {
  init {
    requireLifecycleTimestamp(observedAt)
    requireLifecycleIdentifier(progressId)
    require(label.isNotBlank())
    require(progressId.length <= REVIEW_LIFECYCLE_MAX_DIAGNOSTIC_CHARS)
    require(label.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
  }
}

data class ReviewTerminalCompletion(
  val completedAt: String,
  val status: ReviewProcessOutcome,
) {
  init { requireLifecycleTimestamp(completedAt) }
}

data class ReviewDiagnosticReference(
  val reference: String,
  val summary: String,
) {
  init {
    requireLifecycleIdentifier(reference)
    require(summary.isNotBlank())
    require(reference.length <= REVIEW_LIFECYCLE_MAX_DIAGNOSTIC_CHARS)
    require(summary.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
    val forbidden = listOf("prompt", "transcript", "tool log", "complete diff")
    require(forbidden.none { summary.contains(it, ignoreCase = true) }) {
      "Lifecycle diagnostics must reference bounded evidence, not raw review content."
    }
  }
}

/** One bounded fact in the durable delegated-review lifecycle. */
data class ReviewLifecycleEvent(
  val eventId: String,
  val reviewId: String,
  val sequence: Long,
  val occurredAt: String,
  val component: ReviewLifecycleComponent,
  val eventKind: ReviewLifecycleEventKind,
  val packetDigest: String,
  val workerId: String? = null,
  val providerId: String? = null,
  val attempt: Int? = null,
  val assignmentDigest: String? = null,
  val routedArea: String? = null,
  val state: ReviewWorkerLifecycleState? = null,
  val processOutcome: ReviewProcessOutcome? = null,
  val livenessObservations: List<ReviewLivenessObservation> = emptyList(),
  val providerOutput: ReviewProviderOutputObservation? = null,
  val declaredProgress: ReviewDeclaredSpecialistProgress? = null,
  val durableProgress: ReviewDurableWorkerProgress? = null,
  val terminalCompletion: ReviewTerminalCompletion? = null,
  val diagnostic: ReviewDiagnosticReference? = null,
) {
  init {
    requireLifecycleIdentifier(eventId)
    requireLifecycleIdentifier(reviewId)
    requireLifecycleTimestamp(occurredAt)
    require(sequence >= 1)
    require(packetDigest.matches(Regex("^[a-f0-9]{64}$")))
    require(attempt == null || attempt >= 1)
    require(assignmentDigest == null || assignmentDigest.matches(Regex("^[a-f0-9]{64}$")))
    workerId?.let(::requireLifecycleIdentifier)
    providerId?.let(::requireLifecycleIdentifier)
    routedArea?.let(::requireLifecycleIdentifier)
    require(livenessObservations.size <= 8)
    if (component == ReviewLifecycleComponent.WORKER) {
      require(!workerId.isNullOrBlank() && !providerId.isNullOrBlank())
      require(attempt != null && assignmentDigest != null && !routedArea.isNullOrBlank())
    }
    if (eventKind == ReviewLifecycleEventKind.WORKER_PROGRESS) {
      require(durableProgress != null) {
        "A worker progress transition requires durable specialist progress evidence."
      }
    }
    if (eventKind.name.startsWith("TERMINAL_") || eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      require(terminalCompletion != null) { "Terminal and completed aggregation events require completion evidence." }
    }
    require(providerOutput == null || eventKind != ReviewLifecycleEventKind.WORKER_PROGRESS) {
      "Provider output cannot stand in for durable specialist progress."
    }
  }

  val idempotencyKey: String get() = eventId

  fun toBoundedPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
    "kind" to "lifecycle_event",
    "event_id" to eventId,
    "review_id" to reviewId,
    "sequence" to sequence,
    "occurred_at" to occurredAt,
    "component" to component.name.lowercase(),
    "event_kind" to eventKind.name.lowercase(),
    "packet_digest" to packetDigest,
  ).also { payload ->
    workerId?.let { payload["worker_id"] = it }
    providerId?.let { payload["provider_id"] = it }
    attempt?.let { payload["attempt"] = it }
    assignmentDigest?.let { payload["assignment_digest"] = it }
    routedArea?.let { payload["routed_area"] = it }
    state?.let { payload["state"] = it.name.lowercase() }
    processOutcome?.let { payload["process_outcome"] = it.name.lowercase() }
    if (livenessObservations.isNotEmpty()) payload["liveness_observations"] = livenessObservations.map { it.toPayload() }
    providerOutput?.let { payload["provider_output"] = it.toPayload() }
    declaredProgress?.let { payload["declared_progress"] = it.toPayload() }
    durableProgress?.let { payload["durable_progress"] = it.toPayload() }
    terminalCompletion?.let { payload["terminal_completion"] = it.toPayload() }
    diagnostic?.let { payload["diagnostic"] = it.toPayload() }
  }

  private fun ReviewLivenessObservation.toPayload() = mapOf(
    "kind" to kind.name.lowercase(), "observed_at" to observedAt, "status" to status,
  )
  private fun ReviewProviderOutputObservation.toPayload() = mapOf(
    "observed_at" to observedAt, "outcome" to outcome, "byte_size" to byteSize, "sha256" to sha256,
  )
  private fun ReviewDeclaredSpecialistProgress.toPayload() = mapOf(
    "observed_at" to observedAt, "progress_id" to progressId, "label" to label,
  )
  private fun ReviewDurableWorkerProgress.toPayload() = mapOf(
    "observed_at" to observedAt, "progress_id" to progressId, "label" to label,
  )
  private fun ReviewTerminalCompletion.toPayload() = mapOf(
    "completed_at" to completedAt, "status" to status.name.lowercase(),
  )
  private fun ReviewDiagnosticReference.toPayload() = mapOf("reference" to reference, "summary" to summary)
}

data class ReviewLifecycleEvidencePackage(
  val reviewId: String,
  val packetDigest: String,
  val events: List<ReviewLifecycleEvent>,
) {
  init {
    requireLifecycleIdentifier(reviewId)
    require(packetDigest.matches(Regex("^[a-f0-9]{64}$")))
    require(events.all { it.reviewId == reviewId && it.packetDigest == packetDigest })
    require(events.map { it.eventId }.distinct().size == events.size)
    require(events.map { it.sequence }.distinct().size == events.size)
  }

  fun toEvidencePayload(): Map<String, Any?> = mapOf(
    "contract_version" to REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
    "kind" to "review_lifecycle_evidence",
    "review_id" to reviewId,
    "packet_digest" to packetDigest,
    "events" to events.sortedBy { it.sequence }.map(ReviewLifecycleEvent::toBoundedPayload),
  )
}

/** Replays durable events and rejects conflicting or out-of-order lifecycle transitions. */
class ReviewLifecycleLedger(initialEvents: List<ReviewLifecycleEvent> = emptyList()) {
  private val recorded = linkedMapOf<String, ReviewLifecycleEvent>()
  private var nextSequence = 1L

  init { initialEvents.sortedBy { it.sequence }.forEach(::append) }

  val events: List<ReviewLifecycleEvent> get() = recorded.values.sortedBy { it.sequence }

  fun append(event: ReviewLifecycleEvent): Boolean {
    recorded[event.idempotencyKey]?.let { existing ->
      require(existing == event) { "Lifecycle event '${event.eventId}' was replayed with different evidence." }
      return false
    }
    require(event.sequence == nextSequence) {
      "Lifecycle event '${event.eventId}' has sequence ${event.sequence}; expected $nextSequence."
    }
    if (event.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      require(events.none { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED })
      require(events.filter { it.component == ReviewLifecycleComponent.WORKER }.all { worker ->
        worker.eventKind !in setOf(
          ReviewLifecycleEventKind.WORKER_FAILED,
          ReviewLifecycleEventKind.WORKER_TIMED_OUT,
          ReviewLifecycleEventKind.WORKER_CANCELLED,
          ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
          ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
        )
      }) { "Failed worker evidence cannot be promoted to successful aggregation." }
    }
    recorded[event.idempotencyKey] = event
    nextSequence += 1
    return true
  }

  fun canAggregate(selectedAssignmentDigests: Set<String>): Boolean {
    val completed = events.filter { it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED }
      .mapNotNull { it.assignmentDigest }
      .toSet()
    val failed = events.any {
      it.eventKind in setOf(
        ReviewLifecycleEventKind.WORKER_FAILED,
        ReviewLifecycleEventKind.WORKER_TIMED_OUT,
        ReviewLifecycleEventKind.WORKER_CANCELLED,
        ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
        ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
      )
    }
    return selectedAssignmentDigests.isNotEmpty() && !failed && completed == selectedAssignmentDigests
  }
}

data class ReviewEvidenceRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String? = null,
  val authorizedExpansion: ReviewExpansionRecord? = null,
  val offset: Long? = null,
  val limit: Long? = null,
  val paginationToken: String? = null,
)

data class ReviewExpansionAuthorizationRequest(
  val lane: String,
  val path: String,
  val reachabilityReason: String,
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
