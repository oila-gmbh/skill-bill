package skillbill.application.featuretask

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticPermissions
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
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
)

class RejectedOutputDiagnosticService(
  private val repository: RejectedOutputDiagnosticRepository,
  private val permissions: RejectedOutputDiagnosticPermissions,
  private val config: RejectedOutputDiagnosticConfig = RejectedOutputDiagnosticConfig(),
  private val clock: Clock = Clock.systemUTC(),
) {
  fun record(request: RejectedOutputDiagnosticRequest): RejectedOutputDiagnostic {
    validate(request)
    val identity = stableIdentity(request.workflowId, request.phaseId, request.attempt)
    val digest = sha256(request.rawResponse)
    val oversized = request.rawResponse.size.toLong() > config.maximumPayloadBytes
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
      byteSize = request.rawResponse.size.toLong(),
      sha256 = digest,
      lifecycle = if (oversized) RejectedOutputLifecycle.OVERSIZED else RejectedOutputLifecycle.STORED,
    )
    validate(metadata)
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

  fun inspect(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    repository.select(validate(selector)).onEach(::validate)

  fun readRaw(identity: String): ByteArray {
    val record = repository.read(identity)
    validate(record.metadata)
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

  fun cleanup(now: Instant = clock.instant()): Int =
    repository.markExpired(now.minus(config.retention))

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
  }

  private fun validate(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticSelector {
    if (selector.workflowId.isBlank()) {
      throw RejectedOutputDiagnosticError.InvalidRequest("workflowId must be non-blank")
    }
    if (selector.phaseId?.isBlank() == true) {
      throw RejectedOutputDiagnosticError.InvalidRequest("phaseId must be non-blank when present")
    }
    if (selector.attempt != null && selector.attempt <= 0) {
      throw RejectedOutputDiagnosticError.InvalidRequest("attempt must be positive when present")
    }
    return selector
  }

  private fun validate(metadata: RejectedOutputDiagnostic) {
    val required = listOf(
      metadata.identity,
      metadata.workflowId,
      metadata.phaseId,
      metadata.rule,
      metadata.path,
      metadata.reason,
      metadata.agentId,
      metadata.model,
    )
    val validIdentity = metadata.identity.matches(Regex("^rod_[0-9a-f]{64}$"))
    val validDigest = metadata.sha256.matches(Regex("^[0-9a-f]{64}$"))
    if (
      required.any(String::isBlank) || !validIdentity || metadata.attempt <= 0 ||
      metadata.byteSize < 0 || !validDigest
    ) {
      throw RejectedOutputDiagnosticError.Corrupt(metadata.identity.ifBlank { "<invalid>" })
    }
  }

  companion object {
    fun stableIdentity(workflowId: String, phaseId: String, attempt: Int): String =
      "rod_${sha256("$workflowId\u0000$phaseId\u0000$attempt".encodeToByteArray())}"

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
