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
    require(maximumPayloadBytes >= 0)
    require(!retention.isNegative)
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
    require(request.workflowId.isNotBlank() && request.phaseId.isNotBlank())
    require(request.attempt > 0)
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
    repository.select(selector)

  fun readRaw(identity: String): ByteArray {
    val record = repository.read(identity)
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
    repository.delete(selector)

  companion object {
    fun stableIdentity(workflowId: String, phaseId: String, attempt: Int): String =
      "rod_${sha256("$workflowId\u0000$phaseId\u0000$attempt".encodeToByteArray())}"

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
