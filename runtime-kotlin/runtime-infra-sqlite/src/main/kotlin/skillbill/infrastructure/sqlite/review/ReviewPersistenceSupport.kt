package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.contracts.review.REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION
import skillbill.error.InvalidReviewLifecycleEvidenceSchemaError
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.ReviewDeclaredSpecialistProgress
import skillbill.ports.review.model.ReviewDiagnosticReference
import skillbill.ports.review.model.ReviewDurableWorkerProgress
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLivenessObservation
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewProviderOutputObservation
import skillbill.ports.review.model.ReviewTerminalCompletion
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewSummary
import java.sql.Connection

object ReviewPersistenceSupport {
  fun append(connection: Connection, event: ReviewLifecycleEvent): Boolean {
    val bounded = event.toBoundedPayload()
    validateLifecyclePayload(bounded, "sqlite review lifecycle event '${event.eventId}'")
    val payload = JsonSupport.mapToJsonString(bounded)
    connection.prepareStatement(
      """
    INSERT OR IGNORE INTO review_lifecycle_events
      (event_id, review_id, sequence, packet_digest, occurred_at, payload_json)
    VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, event.eventId)
      statement.setString(PARAM_TWO, event.reviewId)
      statement.setLong(PARAM_THREE, event.sequence)
      statement.setString(PARAM_FOUR, event.packetDigest)
      statement.setString(PARAM_FIVE, event.occurredAt)
      statement.setString(PARAM_SIX, payload)
      val inserted = statement.executeUpdate() == PARAM_ONE
      if (inserted) return true
    }
    connection.prepareStatement(
      "SELECT payload_json FROM review_lifecycle_events WHERE event_id = ?",
    ).use { statement ->
      statement.setString(PARAM_ONE, event.eventId)
      statement.executeQuery().use { rows ->
        require(rows.next()) { "Lifecycle event '${event.eventId}' disappeared during idempotent append." }
        require(rows.getString("payload_json") == payload) {
          "Lifecycle event '${event.eventId}' was replayed with different bounded evidence."
        }
      }
    }
    return false
  }

  fun load(connection: Connection, reviewId: String): List<ReviewLifecycleEvent> = connection.prepareStatement(
    "SELECT payload_json FROM review_lifecycle_events WHERE review_id = ? ORDER BY sequence",
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewId)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(decodeLifecycleEvent(rows.getString("payload_json"), "sqlite review lifecycle event '$reviewId'"))
        }
      }
    }
  }

  private fun decodeLifecycleEvent(rawJson: String, sourceLabel: String): ReviewLifecycleEvent {
    val value = JsonSupport.parseObjectOrNull(rawJson)?.let(JsonSupport::jsonElementToValue)
    val payload = JsonSupport.anyToStringAnyMap(value)
      ?: throw InvalidReviewLifecycleEvidenceSchemaError(sourceLabel, "Stored payload is not a JSON object.")
    validateLifecyclePayload(payload, sourceLabel)
    return runCatching { decodeValidatedLifecycleEvent(payload) }
      .getOrElse { error -> throw invalidLifecycleModel(sourceLabel, error) }
  }

  private fun invalidLifecycleModel(sourceLabel: String, error: Throwable) = InvalidReviewLifecycleEvidenceSchemaError(
    sourceLabel,
    error.message ?: "Stored lifecycle event violates its bounded model.",
    error,
  )

  private fun decodeValidatedLifecycleEvent(payload: Map<String, Any?>): ReviewLifecycleEvent {
    val reader = LifecyclePayloadReader(payload)
    return ReviewLifecycleEvent(
      eventId = reader.requiredString("event_id"),
      reviewId = reader.requiredString("review_id"),
      sequence = (payload["sequence"] as Number).toLong(),
      occurredAt = reader.requiredString("occurred_at"),
      component = ReviewLifecycleComponent.valueOf(reader.requiredString("component").uppercase()),
      eventKind = ReviewLifecycleEventKind.valueOf(reader.requiredString("event_kind").uppercase()),
      packetDigest = reader.requiredString("packet_digest"),
      workerId = reader.optionalString("worker_id"),
      providerId = reader.optionalString("provider_id"),
      attempt = (payload["attempt"] as? Number)?.toInt(),
      assignmentDigest = reader.optionalString("assignment_digest"),
      routedArea = reader.optionalString("routed_area"),
      waveNumber = (payload["wave_number"] as? Number)?.toInt(),
      state = reader.optionalString("state")?.uppercase()?.let(ReviewWorkerLifecycleState::valueOf),
      processOutcome = reader.optionalString("process_outcome")?.uppercase()?.let(ReviewProcessOutcome::valueOf),
      livenessObservations = reader.livenessObservations(),
      providerOutput = reader.providerOutput(),
      declaredProgress = reader.declaredProgress(),
      durableProgress = reader.durableProgress(),
      resultEnvelope = reader.objectMap("result_envelope")?.let(ReviewWorkerResultEnvelope::fromPayload),
      terminalCompletion = reader.terminalCompletion(),
      diagnostic = reader.diagnostic(),
    )
  }

  private class LifecyclePayloadReader(private val payload: Map<String, Any?>) {
    fun requiredString(key: String) = payload[key] as? String
      ?: error("Lifecycle event field '$key' is missing.")

    fun optionalString(key: String) = payload[key] as? String

    @Suppress("UNCHECKED_CAST")
    fun objectMap(key: String) = payload[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun objectList(key: String) = payload[key] as? List<Map<String, Any?>> ?: emptyList()

    fun livenessObservations() = objectList("liveness_observations").map { observation ->
      ReviewLivenessObservation(
        ReviewLivenessObservation.Kind.valueOf((observation["kind"] as String).uppercase()),
        observation["observed_at"] as String,
        observation["status"] as String,
      )
    }

    fun providerOutput() = objectMap("provider_output")?.let { output ->
      ReviewProviderOutputObservation(
        output["observed_at"] as String,
        output["outcome"] as String,
        (output["byte_size"] as Number).toLong(),
        output["sha256"] as String,
      )
    }

    fun declaredProgress() = objectMap("declared_progress")?.let { progress ->
      ReviewDeclaredSpecialistProgress(
        progress["observed_at"] as String,
        progress["progress_id"] as String,
        progress["label"] as String,
      )
    }

    fun durableProgress() = objectMap("durable_progress")?.let { progress ->
      ReviewDurableWorkerProgress(
        progress["observed_at"] as String,
        progress["progress_id"] as String,
        progress["label"] as String,
      )
    }

    fun terminalCompletion() = objectMap("terminal_completion")?.let { terminal ->
      ReviewTerminalCompletion(
        terminal["completed_at"] as String,
        ReviewProcessOutcome.valueOf((terminal["status"] as String).uppercase()),
      )
    }

    fun diagnostic() = objectMap("diagnostic")?.let { diagnostic ->
      ReviewDiagnosticReference(
        diagnostic["reference"] as String,
        diagnostic["summary"] as String,
      )
    }
  }

  fun saveLifecycleSnapshot(connection: Connection, snapshot: DelegatedReviewLifecycleSnapshot) {
    val payload = snapshot.toPayload()
    ReviewLifecycleSnapshotCodec.validate(
      payload,
      "sqlite delegated review lifecycle '${snapshot.reviewId}'",
    )
    connection.prepareStatement(
      """
      INSERT INTO review_delegated_lifecycle (
        review_id, packet_digest, contract_version, bounded_payload_json
      ) VALUES (?, ?, ?, ?)
      ON CONFLICT(review_id) DO UPDATE SET
        packet_digest = excluded.packet_digest,
        contract_version = excluded.contract_version,
        bounded_payload_json = excluded.bounded_payload_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, snapshot.reviewId)
      statement.setString(PARAM_TWO, snapshot.packetDigest)
      statement.setString(PARAM_THREE, payload["contract_version"] as String)
      statement.setString(PARAM_FOUR, JsonSupport.mapToJsonString(payload))
      statement.executeUpdate()
    }
  }

  fun loadLifecycleSnapshot(connection: Connection, reviewId: String): DelegatedReviewLifecycleSnapshot? =
    connection.prepareStatement(
      "SELECT bounded_payload_json FROM review_delegated_lifecycle WHERE review_id = ?",
    ).use { statement ->
      statement.setString(PARAM_ONE, reviewId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val sourceLabel = "sqlite delegated review lifecycle '$reviewId'"
        ReviewLifecycleSnapshotCodec.decode(rows.getString("bounded_payload_json"), sourceLabel)
      }
    }

  private val lifecyclePayloadKeys = setOf(
    "contract_version",
    "kind",
    "event_id",
    "review_id",
    "sequence",
    "occurred_at",
    "component",
    "event_kind",
    "packet_digest",
    "worker_id",
    "provider_id",
    "attempt",
    "assignment_digest",
    "routed_area",
    "wave_number",
    "state",
    "process_outcome",
    "liveness_observations",
    "provider_output",
    "declared_progress",
    "durable_progress",
    "result_envelope",
    "terminal_completion",
    "diagnostic",
  )

  private val requiredLifecyclePayloadKeys = setOf(
    "contract_version",
    "kind",
    "event_id",
    "review_id",
    "sequence",
    "occurred_at",
    "component",
    "event_kind",
    "packet_digest",
  )

  private fun validateLifecyclePayload(payload: Map<String, Any?>, sourceLabel: String) {
    try {
      require(payload.keys.containsAll(requiredLifecyclePayloadKeys)) {
        "Lifecycle event is missing required bounded fields: ${requiredLifecyclePayloadKeys - payload.keys}."
      }
      require(payload.keys.all { it in lifecyclePayloadKeys }) {
        "Lifecycle event contains fields outside the bounded contract: ${payload.keys - lifecyclePayloadKeys}."
      }
      require(payload["contract_version"] == REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION) {
        "Lifecycle event contract_version must be $REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION."
      }
      require(payload["kind"] == "lifecycle_event") { "Lifecycle event kind must be lifecycle_event." }
      require(payload["event_id"] is String)
      require(payload["review_id"] is String)
      require(payload["sequence"] is Number)
      require(payload["occurred_at"] is String)
      require(payload["component"] is String)
      require(payload["event_kind"] is String)
      require(payload["packet_digest"] is String)
      if (payload.containsKey("liveness_observations")) {
        val observations = payload["liveness_observations"] as? List<*>
          ?: error("Lifecycle liveness_observations must be an array.")
        observations.forEach { value ->
          validateNestedKeys(value, setOf("kind", "observed_at", "status"), sourceLabel)
        }
      }
      validatePresentNested(
        payload,
        "provider_output",
        setOf("observed_at", "outcome", "byte_size", "sha256"),
        sourceLabel,
      )
      validatePresentNested(payload, "declared_progress", setOf("observed_at", "progress_id", "label"), sourceLabel)
      validatePresentNested(payload, "durable_progress", setOf("observed_at", "progress_id", "label"), sourceLabel)
      validatePresentNested(payload, "result_envelope", setOf("findings"), sourceLabel)
      validatePresentNested(payload, "terminal_completion", setOf("completed_at", "status"), sourceLabel)
      validatePresentNested(payload, "diagnostic", setOf("reference", "summary"), sourceLabel)
    } catch (error: IllegalArgumentException) {
      throw InvalidReviewLifecycleEvidenceSchemaError(
        sourceLabel,
        error.message ?: "Stored lifecycle event violates the bounded schema.",
        error,
      )
    }
  }

  private fun validateNestedKeys(value: Any?, expected: Set<String>, sourceLabel: String) {
    if (value == null) return
    val map = value as? Map<*, *> ?: throw InvalidReviewLifecycleEvidenceSchemaError(
      sourceLabel,
      "Lifecycle nested evidence must be an object.",
    )
    require(map.keys.filterIsInstance<String>().toSet() == expected) {
      "Lifecycle nested evidence fields must match the bounded contract."
    }
  }

  private fun validatePresentNested(
    payload: Map<String, Any?>,
    key: String,
    expected: Set<String>,
    sourceLabel: String,
  ) {
    if (!payload.containsKey(key)) return
    require(payload[key] != null) { "Lifecycle field '$key' must be an object when present." }
    validateNestedKeys(payload[key], expected, sourceLabel)
  }
}

fun upsertReviewAccounting(connection: Connection, record: ReviewAccountingRecord) {
  connection.prepareStatement(
    """
    INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(review_id) DO UPDATE SET
      packet_digest = excluded.packet_digest,
      bounded_payload_json = excluded.bounded_payload_json,
      updated_at = CURRENT_TIMESTAMP
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, record.reviewId)
    statement.setString(PARAM_TWO, record.packetDigest)
    statement.setString(PARAM_THREE, JsonSupport.mapToJsonString(record.boundedPayload))
    statement.executeUpdate()
  }
}

fun loadReviewAccounting(connection: Connection, reviewId: String): ReviewAccountingRecord? =
  connection.prepareStatement(
    "SELECT packet_digest, bounded_payload_json FROM review_accounting WHERE review_id = ?",
  ).use { statement ->
    statement.setString(1, reviewId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return@use null
      ReviewAccountingRecord(
        reviewId,
        rows.getString("packet_digest"),
        requireNotNull(decodeBoundedAccounting(rows.getString("bounded_payload_json"))) {
          "Malformed bounded review accounting for '$reviewId'."
        },
      )
    }
  }

// The bounded-payload contract is expressed in plain Kotlin values, so a stored row is decoded all
// the way down before it reaches the record's validator.
private fun decodeBoundedAccounting(rawJson: String): Map<String, Any?>? = JsonSupport.parseObjectOrNull(rawJson)?.let {
  JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
}

fun existingReviewSummary(connection: Connection, reviewRunId: String): ReviewSummary? =
  connection.prepareStatement(reviewSummarySql).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) resultSet.toReviewSummary() else null
    }
  }

fun reviewSummaryChanged(
  existingReviewSummary: ReviewSummary?,
  review: ImportedReview,
  existingFindings: List<ImportedFinding>,
): Boolean = existingReviewSummary == null ||
  existingReviewSummary.reviewSessionId != review.reviewSessionId ||
  existingReviewSummary.routedSkill != review.routedSkill ||
  existingReviewSummary.detectedScope != review.detectedScope ||
  existingReviewSummary.detectedStack != review.detectedStack ||
  existingReviewSummary.executionMode != review.executionMode ||
  existingReviewSummary.specialistReviewsRaw != review.specialistReviews.joinToString(",") ||
  existingFindings != review.findings

fun upsertReviewRun(connection: Connection, review: ImportedReview, sourcePath: String?) {
  connection.prepareStatement(
    """
    INSERT INTO review_runs (
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      specialist_reviews,
      source_path,
      raw_text
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id) DO UPDATE SET
      review_session_id = excluded.review_session_id,
      routed_skill = excluded.routed_skill,
      detected_scope = excluded.detected_scope,
      detected_stack = excluded.detected_stack,
      execution_mode = excluded.execution_mode,
      specialist_reviews = excluded.specialist_reviews,
      source_path = excluded.source_path,
      raw_text = excluded.raw_text
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.setString(PARAM_TWO, review.reviewSessionId)
    statement.setString(PARAM_THREE, review.routedSkill)
    statement.setString(PARAM_FOUR, review.detectedScope)
    statement.setString(PARAM_FIVE, review.detectedStack)
    statement.setString(PARAM_SIX, review.executionMode)
    statement.setString(PARAM_SEVEN, review.specialistReviews.joinToString(","))
    statement.setString(PARAM_EIGHT, sourcePath)
    statement.setString(PARAM_NINE, review.rawText)
    statement.executeUpdate()
  }
}

fun replaceFindings(connection: Connection, review: ImportedReview) {
  connection.prepareStatement("DELETE FROM findings WHERE review_run_id = ?").use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.executeUpdate()
  }
  review.findings.forEach { finding ->
    connection.prepareStatement(
      """
      INSERT INTO findings (
        review_run_id,
        finding_id,
        severity,
        confidence,
        issue_category,
        location,
        description,
        finding_text
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, review.reviewRunId)
      statement.setString(PARAM_TWO, finding.findingId)
      statement.setString(PARAM_THREE, finding.severity)
      statement.setString(PARAM_FOUR, finding.confidence)
      statement.setString(PARAM_FIVE, finding.issueCategory)
      statement.setString(PARAM_SIX, finding.location)
      statement.setString(PARAM_SEVEN, finding.description)
      statement.setString(PARAM_EIGHT, finding.findingText)
      statement.executeUpdate()
    }
  }
}

fun java.sql.ResultSet.toImportedFinding(): ImportedFinding = ImportedFinding(
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  issueCategory = getString("issue_category"),
  location = getString("location"),
  description = getString("description"),
  findingText = getString("finding_text"),
)

fun java.sql.ResultSet.toReviewSummary(): ReviewSummary = ReviewSummary(
  reviewRunId = getString("review_run_id"),
  reviewSessionId = getString("review_session_id"),
  routedSkill = getString("routed_skill"),
  detectedScope = getString("detected_scope"),
  detectedStack = getString("detected_stack"),
  executionMode = getString("execution_mode"),
  specialistReviewsRaw = getString("specialist_reviews"),
  reviewFinishedAt = getString("review_finished_at"),
  reviewFinishedEventEmittedAt = getString("review_finished_event_emitted_at"),
  orchestratedRun = getBoolean("orchestrated_run"),
)

fun java.sql.ResultSet.toNumberedFinding(number: Int): NumberedFinding = NumberedFinding(
  number = number,
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  location = getString("location"),
  description = getString("description"),
)
