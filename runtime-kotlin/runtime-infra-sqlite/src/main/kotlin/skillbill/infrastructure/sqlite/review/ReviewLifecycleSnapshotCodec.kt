package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.contracts.review.REVIEW_LIFECYCLE_CONTRACT_VERSION
import skillbill.contracts.review.ReviewLifecycleSchemaValidator
import skillbill.error.InvalidReviewLifecycleSchemaError
import skillbill.ports.review.model.DelegatedReviewDeadline
import skillbill.ports.review.model.DelegatedReviewDeadlineScope
import skillbill.ports.review.model.DelegatedReviewLifecycleMetrics
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.DelegatedReviewTerminalClassification
import skillbill.ports.review.model.DelegatedReviewWaveRecord
import skillbill.ports.review.model.DelegatedReviewWorkerRecord
import skillbill.ports.review.model.DelegatedReviewWorkerState

internal object ReviewLifecycleSnapshotCodec {
  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    ReviewLifecycleSchemaValidator.validate(payload, sourceLabel)
  }

  fun decode(raw: String, sourceLabel: String): DelegatedReviewLifecycleSnapshot {
    val value = JsonSupport.parseObjectOrNull(raw)?.let(JsonSupport::jsonElementToValue)
    val payload = JsonSupport.anyToStringAnyMap(value)
      ?: throw InvalidReviewLifecycleSchemaError(sourceLabel, "Stored payload is not a JSON object.")
    validate(payload, sourceLabel)
    return runCatching { decodeSnapshot(payload) }.getOrElse { error ->
      throw InvalidReviewLifecycleSchemaError(
        sourceLabel,
        error.message ?: "Stored delegated review lifecycle violates its model.",
        error,
      )
    }
  }

  private fun decodeSnapshot(payload: Map<String, Any?>): DelegatedReviewLifecycleSnapshot {
    validateRoot(payload)
    fun string(key: String) = payload[key] as? String ?: error("Lifecycle field '$key' is missing.")
    fun number(key: String) = (payload[key] as? Number)?.toLong()
      ?: error("Lifecycle field '$key' is not numeric.")
    return DelegatedReviewLifecycleSnapshot(
      reviewId = string("review_id"),
      packetDigest = string("packet_digest"),
      selectedAreaCount = number("selected_area_count").toInt(),
      predictedWaveCount = number("predicted_wave_count").toInt(),
      actualWaveCount = number("actual_wave_count").toInt(),
      coordinatorSlots = number("coordinator_slots").toInt(),
      workers = decodeWorkers(objects(payload, "workers")),
      waves = decodeWaves(objects(payload, "waves")),
      deadlines = decodeDeadlines(objects(payload, "deadlines")),
      metrics = decodeMetrics(payload),
      terminalClassification = (payload["terminal_classification"] as? String)?.let {
        DelegatedReviewTerminalClassification.valueOf(it.uppercase())
      },
    )
  }

  private fun validateRoot(payload: Map<String, Any?>) {
    requireKeys(
      payload,
      setOf(
        "contract_version", "kind", "review_id", "packet_digest", "selected_area_count",
        "predicted_wave_count", "actual_wave_count", "coordinator_slots", "workers", "waves",
        "deadlines", "metrics", "terminal_classification",
      ),
      "lifecycle",
      optional = setOf("terminal_classification"),
    )
    require(payload["contract_version"] == REVIEW_LIFECYCLE_CONTRACT_VERSION)
    require(payload["kind"] == "delegated_review_lifecycle")
  }

  private fun objects(payload: Map<String, Any?>, key: String): List<Map<String, Any?>> =
    (payload[key] as? List<*>)?.map { value ->
      JsonSupport.anyToStringAnyMap(value) ?: error("Lifecycle field '$key' contains a non-object.")
    } ?: error("Lifecycle field '$key' is not an array.")

  private fun decodeWorkers(workers: List<Map<String, Any?>>) = workers.map { worker ->
    requireKeys(
      worker,
      setOf("worker_id", "provider_id", "assignment_digest", "attempt", "area", "state", "diagnostic"),
      "worker",
      optional = setOf("diagnostic"),
    )
    DelegatedReviewWorkerRecord(
      workerId = worker["worker_id"] as String,
      providerId = worker["provider_id"] as String,
      assignmentDigest = worker["assignment_digest"] as String,
      attempt = (worker["attempt"] as Number).toInt(),
      area = worker["area"] as String,
      state = DelegatedReviewWorkerState.valueOf((worker["state"] as String).uppercase()),
      diagnostic = worker["diagnostic"] as? String,
    )
  }

  private fun decodeWaves(waves: List<Map<String, Any?>>) = waves.map { wave ->
    requireKeys(wave, setOf("wave_number", "worker_ids"), "wave")
    DelegatedReviewWaveRecord(
      waveNumber = (wave["wave_number"] as Number).toInt(),
      workerIds = (wave["worker_ids"] as? List<*>)?.map { it as String }
        ?: error("Lifecycle wave worker_ids is not an array."),
    )
  }

  private fun decodeDeadlines(deadlines: List<Map<String, Any?>>) = deadlines.map { deadline ->
    requireKeys(deadline, setOf("scope", "limit_ms"), "deadline")
    DelegatedReviewDeadline(
      scope = DelegatedReviewDeadlineScope.valueOf((deadline["scope"] as String).uppercase()),
      limitMs = (deadline["limit_ms"] as Number).toLong(),
    )
  }

  private fun decodeMetrics(payload: Map<String, Any?>): DelegatedReviewLifecycleMetrics {
    val metrics = JsonSupport.anyToStringAnyMap(payload["metrics"])
      ?: error("Lifecycle metrics are missing.")
    requireKeys(
      metrics,
      setOf(
        "elapsed_ms",
        "total_tokens",
        "process_count",
        "mcp_startup_count",
        "selected_area_count",
        "completed_area_count",
        "lost_worker_count",
      ),
      "metrics",
    )
    return DelegatedReviewLifecycleMetrics(
      elapsedMs = (metrics["elapsed_ms"] as Number).toLong(),
      totalTokens = (metrics["total_tokens"] as Number).toLong(),
      processCount = (metrics["process_count"] as Number).toInt(),
      mcpStartupCount = (metrics["mcp_startup_count"] as Number).toInt(),
      selectedAreaCount = (metrics["selected_area_count"] as Number).toInt(),
      completedAreaCount = (metrics["completed_area_count"] as Number).toInt(),
      lostWorkerCount = (metrics["lost_worker_count"] as Number).toInt(),
    )
  }

  private fun requireKeys(
    value: Map<String, Any?>,
    allowed: Set<String>,
    label: String,
    optional: Set<String> = emptySet(),
  ) {
    val required = allowed - optional
    require(value.keys.containsAll(required)) { "Delegated review $label is missing required fields." }
    require(value.keys.all { it in allowed }) { "Delegated review $label contains unknown fields." }
  }
}
