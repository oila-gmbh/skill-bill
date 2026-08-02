package skillbill.ports.review.model

import skillbill.contracts.review.REVIEW_LIFECYCLE_CONTRACT_VERSION

private const val MAX_REVIEW_LIFECYCLE_WORKERS = 128
private const val MAX_REVIEW_LIFECYCLE_WAVES = 128
private const val MAX_REVIEW_LIFECYCLE_DEADLINES = 8

enum class DelegatedReviewProviderStatus { SUPPORTED, EXPERIMENTAL, UNSUPPORTED }

data class DelegatedReviewCapabilityDimensions(
  val freshContextIsolation: Boolean,
  val workerTracking: Boolean,
  val outputCapture: Boolean,
  val declaredProgress: Boolean,
  val cancellation: Boolean,
  val timeout: Boolean,
  val tokenReporting: Boolean,
  val terminalResult: Boolean,
) {
  val allSatisfied: Boolean
    get() = freshContextIsolation && workerTracking && outputCapture && declaredProgress &&
      cancellation && timeout && tokenReporting && terminalResult

  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "fresh_context_isolation" to freshContextIsolation,
    "worker_tracking" to workerTracking,
    "output_capture" to outputCapture,
    "declared_progress" to declaredProgress,
    "cancellation" to cancellation,
    "timeout" to timeout,
    "token_reporting" to tokenReporting,
    "terminal_result" to terminalResult,
  )
}

data class DelegatedReviewProviderCapability(
  val providerId: String,
  val status: DelegatedReviewProviderStatus,
  val dimensions: DelegatedReviewCapabilityDimensions,
  val rationale: String,
) {
  init {
    require(providerId.isNotBlank() && providerId.length <= 200)
    require(rationale.isNotBlank() && rationale.length <= 500)
    require(status != DelegatedReviewProviderStatus.SUPPORTED || dimensions.allSatisfied) {
      "A supported delegated provider must satisfy every capability dimension."
    }
    require(status != DelegatedReviewProviderStatus.UNSUPPORTED || !dimensions.allSatisfied) {
      "An unsupported delegated provider must identify at least one missing capability."
    }
  }

  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "provider_id" to providerId,
    "status" to status.name.lowercase(),
    "capabilities" to dimensions.toPayload(),
    "rationale" to rationale,
  )
}

data class DelegatedReviewProviderCapabilityMatrix(
  val providers: List<DelegatedReviewProviderCapability>,
) {
  init {
    require(providers.isNotEmpty())
    require(providers.map { it.providerId }.distinct().size == providers.size)
  }

  fun toPayload(): Map<String, Any?> = mapOf(
    "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
    "kind" to "provider_capability_matrix",
    "providers" to providers.map(DelegatedReviewProviderCapability::toPayload),
  )
}

enum class DelegatedReviewWorkerState {
  SELECTED,
  QUEUED,
  LAUNCHED,
  RUNNING,
  COMPLETED,
  FAILED,
  TIMED_OUT,
  CANCELLED,
  AGGREGATED,
}

enum class DelegatedReviewDeadlineScope { STARTUP, PROGRESS_IDLE, PER_WORKER, AGGREGATION, WHOLE_REVIEW }

enum class DelegatedReviewTerminalClassification {
  COMPLETED,
  FAILED,
  TIMED_OUT,
  CANCELLED,
  BLOCKED_UNSUPPORTED,
  BLOCKED_AGGREGATION,
  INTERRUPTED_BEFORE_LAUNCH,
  INTERRUPTED_DURING_WORKER,
  INTERRUPTED_BETWEEN_WAVES,
  INTERRUPTED_DURING_AGGREGATION,
  INTERRUPTED_BEFORE_TERMINAL_PERSISTENCE,
}

data class DelegatedReviewWorkerRecord(
  val workerId: String,
  val providerId: String,
  val assignmentDigest: String,
  val attempt: Int,
  val area: String,
  val state: DelegatedReviewWorkerState,
  val diagnostic: String? = null,
) {
  init {
    require(workerId.isNotBlank() && providerId.isNotBlank() && area.isNotBlank())
    require(assignmentDigest.matches(Regex("^[a-f0-9]{64}$")))
    require(attempt >= 1)
    diagnostic?.let { require(it.isNotBlank() && it.length <= 500) }
  }

  fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "worker_id" to workerId,
    "provider_id" to providerId,
    "assignment_digest" to assignmentDigest,
    "attempt" to attempt,
    "area" to area,
    "state" to state.name.lowercase(),
  ).also { payload -> diagnostic?.let { payload["diagnostic"] = it } }
}

data class DelegatedReviewDeadline(
  val scope: DelegatedReviewDeadlineScope,
  val limitMs: Long,
) {
  init {
    require(limitMs > 0)
  }

  fun toPayload(): Map<String, Any?> = mapOf(
    "scope" to scope.name.lowercase(),
    "limit_ms" to limitMs,
  )
}

data class DelegatedReviewWaveRecord(
  val waveNumber: Int,
  val workerIds: List<String>,
) {
  init {
    require(waveNumber >= 1)
    require(workerIds.distinct().size == workerIds.size)
    require(workerIds.all(String::isNotBlank))
  }

  fun toPayload(): Map<String, Any?> = mapOf(
    "wave_number" to waveNumber,
    "worker_ids" to workerIds,
  )
}

data class DelegatedReviewLifecycleMetrics(
  val elapsedMs: Long,
  val totalTokens: Long,
  val processCount: Int,
  val mcpStartupCount: Int,
  val selectedAreaCount: Int,
  val completedAreaCount: Int,
  val lostWorkerCount: Int,
) {
  init {
    require(elapsedMs >= 0 && totalTokens >= 0)
    require(processCount >= 0 && mcpStartupCount >= 0)
    require(selectedAreaCount >= 0 && completedAreaCount >= 0 && lostWorkerCount >= 0)
    require(completedAreaCount <= selectedAreaCount)
  }

  fun toPayload(): Map<String, Any?> = mapOf(
    "elapsed_ms" to elapsedMs,
    "total_tokens" to totalTokens,
    "process_count" to processCount,
    "mcp_startup_count" to mcpStartupCount,
    "selected_area_count" to selectedAreaCount,
    "completed_area_count" to completedAreaCount,
    "lost_worker_count" to lostWorkerCount,
  )
}

data class DelegatedReviewLifecycleSnapshot(
  val reviewId: String,
  val packetDigest: String,
  val selectedAreaCount: Int,
  val predictedWaveCount: Int,
  val actualWaveCount: Int,
  val coordinatorSlots: Int,
  val workers: List<DelegatedReviewWorkerRecord>,
  val waves: List<DelegatedReviewWaveRecord>,
  val deadlines: List<DelegatedReviewDeadline>,
  val metrics: DelegatedReviewLifecycleMetrics,
  val terminalClassification: DelegatedReviewTerminalClassification? = null,
) {
  init {
    require(reviewId.isNotBlank())
    require(packetDigest.matches(Regex("^[a-f0-9]{64}$")))
    require(selectedAreaCount >= 0 && predictedWaveCount >= 0 && actualWaveCount >= 0)
    require(coordinatorSlots >= 1)
    require(workers.size <= MAX_REVIEW_LIFECYCLE_WORKERS)
    require(waves.size <= MAX_REVIEW_LIFECYCLE_WAVES)
    require(deadlines.size <= MAX_REVIEW_LIFECYCLE_DEADLINES)
    require(workers.map { it.workerId }.distinct().size == workers.size)
    require(waves.map { it.waveNumber } == waves.map { it.waveNumber }.distinct().sorted())
    require(workers.size == selectedAreaCount)
    val workerIds = workers.map { it.workerId }.toSet()
    val waveWorkerIds = waves.flatMap { it.workerIds }
    require(waveWorkerIds.distinct().size == waveWorkerIds.size)
    require(waveWorkerIds.all { it in workerIds })
    require(metrics.selectedAreaCount == selectedAreaCount)
    require(actualWaveCount == waves.size)
    require(actualWaveCount <= predictedWaveCount)
  }

  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "contract_version" to REVIEW_LIFECYCLE_CONTRACT_VERSION,
    "kind" to "delegated_review_lifecycle",
    "review_id" to reviewId,
    "packet_digest" to packetDigest,
    "selected_area_count" to selectedAreaCount,
    "predicted_wave_count" to predictedWaveCount,
    "actual_wave_count" to actualWaveCount,
    "coordinator_slots" to coordinatorSlots,
    "workers" to workers.map(DelegatedReviewWorkerRecord::toPayload),
    "waves" to waves.map(DelegatedReviewWaveRecord::toPayload),
    "deadlines" to deadlines.map(DelegatedReviewDeadline::toPayload),
    "metrics" to metrics.toPayload(),
  ).also { payload ->
    terminalClassification?.let { payload["terminal_classification"] = it.name.lowercase() }
  }
}
