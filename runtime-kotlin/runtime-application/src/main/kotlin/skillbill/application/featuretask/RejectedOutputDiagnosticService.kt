package skillbill.application.featuretask

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticPermissions
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.ProducerOutputEvidence
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class RejectedOutputDiagnosticConfig(
  val maximumPayloadBytes: Long = 1_048_576,
  val retention: Duration = Duration.ofDays(14),
) {
  init {
    if (maximumPayloadBytes < 0) {
      throw RejectedOutputDiagnosticError.InvalidConfiguration("maximumPayloadBytes must be non-negative")
    }
    if (retention.isNegative) {
      throw RejectedOutputDiagnosticError.InvalidConfiguration("retention must be non-negative")
    }
  }
}

data class RejectedOutputDiagnosticRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val rule: String,
  val path: String,
  val reason: String,
  val agentId: String,
  val model: String,
  val rawResponse: ByteArray,
  val observedByteSize: Long = rawResponse.size.toLong(),
  val observedSha256: String = RejectedOutputDiagnosticService.sha256(rawResponse),
  val truncated: Boolean = false,
)

class RejectedOutputDiagnosticService(
  private val repository: RejectedOutputDiagnosticRepository,
  private val permissions: RejectedOutputDiagnosticPermissions,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val config: RejectedOutputDiagnosticConfig = RejectedOutputDiagnosticConfig(),
  private val clock: Clock = Clock.systemUTC(),
) {
  fun record(request: RejectedOutputDiagnosticRequest): RejectedOutputDiagnostic {
    validate(request)
    val identity = stableIdentity(request.workflowId, request.phaseId, request.attempt)
    existing(identity)?.let { record ->
      if (!record.matches(request)) throw RejectedOutputDiagnosticError.Conflict(identity)
      metadataValidator.validate(record.metadata)
      return record.metadata
    }
    cleanup()
    val oversized = request.truncated || request.observedByteSize > config.maximumPayloadBytes
    val metadata = RejectedOutputDiagnostic(
      identity = identity,
      workflowId = request.workflowId,
      phaseId = request.phaseId,
      attempt = request.attempt,
      rule = request.rule,
      path = request.path,
      reason = request.reason,
      agentId = request.agentId,
      model = request.model,
      recordedAt = clock.instant(),
      byteSize = request.observedByteSize,
      sha256 = request.observedSha256,
      lifecycle = if (oversized) RejectedOutputLifecycle.OVERSIZED else RejectedOutputLifecycle.STORED,
    )
    metadataValidator.validate(metadata)
    try {
      permissions.applyRestrictivePermissions()
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: Exception) {
      throw RejectedOutputDiagnosticError.Permission("apply", error)
    }
    return repository.insert(
      RejectedOutputDiagnosticRecord(metadata, request.rawResponse.takeUnless { oversized }),
    ).metadata
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    try {
      permissions.applyRestrictivePermissions()
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: Exception) {
      throw RejectedOutputDiagnosticError.Permission("apply", error)
    }
    cleanup()
    repository.retainProducerOutput(evidence)
  }

  fun inspect(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    repository.select(validate(selector).also { cleanup() }).onEach(metadataValidator::validate)

  fun readRaw(identity: String): ByteArray {
    cleanup()
    val record = repository.read(identity)
    metadataValidator.validate(record.metadata)
    when (record.metadata.lifecycle) {
      RejectedOutputLifecycle.EXPIRED -> throw RejectedOutputDiagnosticError.Expired(identity)
      RejectedOutputLifecycle.OVERSIZED -> throw RejectedOutputDiagnosticError.Oversized(identity)
      RejectedOutputLifecycle.STORED -> Unit
    }
    val payload = record.payload ?: throw RejectedOutputDiagnosticError.Corrupt(identity)
    if (payload.size.toLong() != record.metadata.byteSize || sha256(payload) != record.metadata.sha256) {
      throw RejectedOutputDiagnosticError.Corrupt(identity)
    }
    return payload
  }

  fun cleanup(now: Instant = clock.instant()): Int {
    val cutoff = now.minus(config.retention)
    return repository.markExpired(cutoff) + repository.deleteProducerOutputsBefore(cutoff)
  }

  fun delete(selector: RejectedOutputDiagnosticSelector): Int =
    repository.delete(validate(selector))

  private fun validate(request: RejectedOutputDiagnosticRequest) {
    val required = mapOf(
      "workflowId" to request.workflowId,
      "phaseId" to request.phaseId,
      "rule" to request.rule,
      "path" to request.path,
      "reason" to request.reason,
      "agentId" to request.agentId,
      "model" to request.model,
    )
    required.entries.firstOrNull { it.value.isBlank() }?.let { (field, _) ->
      throw RejectedOutputDiagnosticError.InvalidRequest("$field must be non-blank")
    }
    if (request.attempt <= 0) {
      throw RejectedOutputDiagnosticError.InvalidRequest("attempt must be positive")
    }
    if (request.observedByteSize < request.rawResponse.size || request.observedByteSize < 0) {
      throw RejectedOutputDiagnosticError.InvalidRequest("observedByteSize must include all retained bytes")
    }
    if (!Regex("[0-9a-f]{64}").matches(request.observedSha256)) {
      throw RejectedOutputDiagnosticError.InvalidRequest("observedSha256 must be a lowercase SHA-256 digest")
    }
    if (!request.truncated &&
      (request.observedByteSize != request.rawResponse.size.toLong() ||
        request.observedSha256 != sha256(request.rawResponse))
    ) {
      throw RejectedOutputDiagnosticError.InvalidRequest("complete response evidence does not match its bytes")
    }
  }

  private fun existing(identity: String): RejectedOutputDiagnosticRecord? =
    try {
      repository.read(identity)
    } catch (_: RejectedOutputDiagnosticError.Absent) {
      null
    }

  private fun validate(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticSelector {
    if (selector.workflowId.isBlank()) {
      throw RejectedOutputDiagnosticError.InvalidRequest("workflowId must be non-blank")
    }
    if (selector.phaseId?.isBlank() == true) {
      throw RejectedOutputDiagnosticError.InvalidRequest("phaseId must be non-blank when present")
    }
    if (selector.attempt?.let { it <= 0 } == true) {
      throw RejectedOutputDiagnosticError.InvalidRequest("attempt must be positive when present")
    }
    return selector
  }

  companion object {
    fun stableIdentity(workflowId: String, phaseId: String, attempt: Int): String =
      "rod_${sha256("$workflowId\u0000$phaseId\u0000$attempt".encodeToByteArray())}"

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

private fun RejectedOutputDiagnosticRecord.matches(request: RejectedOutputDiagnosticRequest): Boolean =
  metadata.workflowId == request.workflowId &&
    metadata.phaseId == request.phaseId &&
    metadata.attempt == request.attempt &&
    metadata.rule == request.rule &&
    metadata.path == request.path &&
    metadata.reason == request.reason &&
    metadata.agentId == request.agentId &&
    metadata.model == request.model &&
    metadata.byteSize == request.observedByteSize &&
    metadata.sha256 == request.observedSha256
