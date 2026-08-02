package skillbill.ports.review.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.review.REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
private const val REVIEW_LIFECYCLE_MAX_TEXT_CHARS: Int = 500
private const val REVIEW_LIFECYCLE_MAX_DIAGNOSTIC_CHARS: Int = 200
private const val REVIEW_LIFECYCLE_MAX_IDENTIFIER_CHARS: Int = 200
private const val REVIEW_LIFECYCLE_MAX_TIMESTAMP_CHARS: Int = 64
private val REVIEW_LIFECYCLE_TIMESTAMP = Regex("^[0-9T:.+Z-]+$")
private const val REVIEW_RESULT_MAX_FINDINGS: Int = 7
private const val REVIEW_RESULT_MAX_ORIGIN_CHAINS: Int = 8
private const val REVIEW_RESULT_MAX_ORIGIN_CHAIN_DEPTH: Int = 8
private const val REVIEW_LIFECYCLE_MAX_LIVENESS_OBSERVATIONS: Int = 8

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

/** Bounded, replayable worker output used to rebuild a lane after coordinator restart. */
data class ReviewWorkerResultEnvelope(
  val findings: List<ParallelReviewRawFinding>,
) {
  init {
    require(findings.size <= REVIEW_RESULT_MAX_FINDINGS)
    findings.forEach { finding ->
      require(finding.confidence in setOf("High", "Medium", "Low"))
      require(finding.location.isNotBlank() && finding.location.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
      require(finding.description.isNotBlank() && finding.description.length <= REVIEW_LIFECYCLE_MAX_TEXT_CHARS)
      finding.specialistSkillName?.let(::requireLifecycleIdentifier)
      finding.repositoryPath?.let {
        require(it.isNotBlank() && it.length <= REVIEW_LIFECYCLE_MAX_IDENTIFIER_CHARS)
      }
      finding.line?.let { require(it >= 1) }
      require(finding.originLayerChains.size <= REVIEW_RESULT_MAX_ORIGIN_CHAINS)
      finding.originLayerChains.forEach { chain ->
        require(chain.isNotEmpty() && chain.size <= REVIEW_RESULT_MAX_ORIGIN_CHAIN_DEPTH)
        chain.forEach(::requireLifecycleIdentifier)
      }
    }
  }

  @OpenBoundaryMap("Bounded worker result envelope at the review lifecycle schema seam")
  fun toPayload(): Map<String, Any?> = mapOf(
    "findings" to findings.map { finding ->
      linkedMapOf<String, Any?>(
        "severity" to finding.severity.name.lowercase(),
        "confidence" to finding.confidence,
        "location" to finding.location,
        "description" to finding.description,
      ).also { payload ->
        finding.specialistSkillName?.let { payload["specialist_skill_name"] = it }
        if (finding.originLayerChains.isNotEmpty()) payload["origin_layer_chains"] = finding.originLayerChains
        finding.repositoryPath?.let { payload["repository_path"] = it }
        finding.line?.let { payload["line"] = it }
      }
    },
  )

  companion object {
    @OpenBoundaryMap("Bounded worker result envelope decoded from the review lifecycle schema seam")
    fun fromPayload(payload: Map<String, Any?>): ReviewWorkerResultEnvelope {
      val rawFindings = payload["findings"] as? List<*>
        ?: error("Worker result envelope findings must be an array.")
      return ReviewWorkerResultEnvelope(
        rawFindings.map { raw ->
          val finding = raw as? Map<*, *> ?: error("Worker result finding must be an object.")
          fun requiredString(key: String) = finding[key] as? String
            ?: error("Worker result finding field '$key' is missing.")
          val originLayerChains = (finding["origin_layer_chains"] as? List<*>)?.map { rawChain ->
            (rawChain as? List<*>)?.map { it as? String ?: error("Worker result origin layer must be a string.") }
              ?: error("Worker result origin layer chain must be an array.")
          } ?: emptyList()
          ParallelReviewRawFinding(
            severity = ParallelReviewSeverity.valueOf(requiredString("severity").uppercase()),
            confidence = requiredString("confidence"),
            location = requiredString("location"),
            description = requiredString("description"),
            specialistSkillName = finding["specialist_skill_name"] as? String,
            originLayerChains = originLayerChains,
            repositoryPath = finding["repository_path"] as? String,
            line = (finding["line"] as? Number)?.toInt(),
          )
        },
      )
    }
  }
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
  init {
    requireLifecycleTimestamp(completedAt)
  }
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
  val resultEnvelope: ReviewWorkerResultEnvelope? = null,
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
    require(livenessObservations.size <= REVIEW_LIFECYCLE_MAX_LIVENESS_OBSERVATIONS)
    if (component == ReviewLifecycleComponent.WORKER) {
      require(!workerId.isNullOrBlank() && !providerId.isNullOrBlank())
      require(attempt != null && assignmentDigest != null && !routedArea.isNullOrBlank())
    }
    if (eventKind == ReviewLifecycleEventKind.WORKER_PROGRESS) {
      require(durableProgress != null) {
        "A worker progress transition requires durable specialist progress evidence."
      }
    }
    if (resultEnvelope != null) {
      require(component == ReviewLifecycleComponent.WORKER)
      require(eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED)
    }
    if (eventKind.name.startsWith("TERMINAL_") || eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED) {
      require(terminalCompletion != null) { "Terminal and completed aggregation events require completion evidence." }
    }
    require(providerOutput == null || eventKind != ReviewLifecycleEventKind.WORKER_PROGRESS) {
      "Provider output cannot stand in for durable specialist progress."
    }
  }

  val idempotencyKey: String get() = eventId

  @OpenBoundaryMap("Bounded review lifecycle event at the persistence and schema seam")
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
  ).also(::appendIdentityFields).also(::appendEvidenceFields)

  private fun appendIdentityFields(payload: MutableMap<String, Any?>) {
    workerId?.let { payload["worker_id"] = it }
    providerId?.let { payload["provider_id"] = it }
    attempt?.let { payload["attempt"] = it }
    assignmentDigest?.let { payload["assignment_digest"] = it }
    routedArea?.let { payload["routed_area"] = it }
    state?.let { payload["state"] = it.name.lowercase() }
    processOutcome?.let { payload["process_outcome"] = it.name.lowercase() }
  }

  private fun appendEvidenceFields(payload: MutableMap<String, Any?>) {
    if (livenessObservations.isNotEmpty()) {
      payload["liveness_observations"] = livenessObservations.map { it.toPayload() }
    }
    providerOutput?.let { payload["provider_output"] = it.toPayload() }
    declaredProgress?.let { payload["declared_progress"] = it.toPayload() }
    durableProgress?.let { payload["durable_progress"] = it.toPayload() }
    resultEnvelope?.let { payload["result_envelope"] = it.toPayload() }
    terminalCompletion?.let { payload["terminal_completion"] = it.toPayload() }
    diagnostic?.let { payload["diagnostic"] = it.toPayload() }
  }

  private fun ReviewLivenessObservation.toPayload() = mapOf(
    "kind" to kind.name.lowercase(),
    "observed_at" to observedAt,
    "status" to status,
  )
  private fun ReviewProviderOutputObservation.toPayload() = mapOf(
    "observed_at" to observedAt,
    "outcome" to outcome,
    "byte_size" to byteSize,
    "sha256" to sha256,
  )
  private fun ReviewDeclaredSpecialistProgress.toPayload() = mapOf(
    "observed_at" to observedAt,
    "progress_id" to progressId,
    "label" to label,
  )
  private fun ReviewDurableWorkerProgress.toPayload() = mapOf(
    "observed_at" to observedAt,
    "progress_id" to progressId,
    "label" to label,
  )
  private fun ReviewTerminalCompletion.toPayload() = mapOf(
    "completed_at" to completedAt,
    "status" to status.name.lowercase(),
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

  @OpenBoundaryMap("Bounded review lifecycle evidence package at the schema seam")
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

  init {
    initialEvents.sortedBy { it.sequence }.forEach(::append)
  }

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
      require(latestWorkerTerminalEvents().all { worker -> !worker.isFailure() }) {
        "Failed worker evidence cannot be promoted to successful aggregation."
      }
    }
    recorded[event.idempotencyKey] = event
    nextSequence += 1
    return true
  }

  fun canAggregate(selectedAssignmentDigests: Set<String>): Boolean {
    if (events.any { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED }) return false
    val latestWorkerEvents = latestWorkerTerminalEvents()
    val completed = latestWorkerEvents.filter {
      it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED && it.resultEnvelope != null
    }
      .mapNotNull { it.assignmentDigest }
      .toSet()
    val failed = latestWorkerEvents.any { it.isFailure() }
    return selectedAssignmentDigests.isNotEmpty() && !failed && completed == selectedAssignmentDigests
  }

  private fun latestWorkerTerminalEvents(): List<ReviewLifecycleEvent> = events
    .filter { it.component == ReviewLifecycleComponent.WORKER && it.isTerminalWorkerEvent() }
    .groupBy(ReviewLifecycleEvent::assignmentDigest)
    .values
    .map { assignmentEvents ->
      assignmentEvents.maxWithOrNull(compareBy<ReviewLifecycleEvent>({ it.attempt ?: 0 }, { it.sequence }))
        ?: error("Worker lifecycle event group cannot be empty.")
    }

  private fun ReviewLifecycleEvent.isTerminalWorkerEvent(): Boolean = eventKind in setOf(
    ReviewLifecycleEventKind.WORKER_COMPLETED,
    ReviewLifecycleEventKind.WORKER_FAILED,
    ReviewLifecycleEventKind.WORKER_TIMED_OUT,
    ReviewLifecycleEventKind.WORKER_CANCELLED,
    ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
    ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
  )

  private fun ReviewLifecycleEvent.isFailure(): Boolean = eventKind in setOf(
    ReviewLifecycleEventKind.WORKER_FAILED,
    ReviewLifecycleEventKind.WORKER_TIMED_OUT,
    ReviewLifecycleEventKind.WORKER_CANCELLED,
    ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
    ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
  )
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
