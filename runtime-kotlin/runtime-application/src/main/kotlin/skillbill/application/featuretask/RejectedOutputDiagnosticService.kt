package skillbill.application.featuretask

import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticPermissions
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

typealias RejectedOutputDiagnosticConfig =
  skillbill.application.featuretask.model.RejectedOutputDiagnosticConfig
typealias RejectedOutputDiagnosticRequest =
  skillbill.application.featuretask.model.RejectedOutputDiagnosticRequest

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
    repository.readOrNull(identity)?.let { record ->
      if (!record.matches(request)) throw RejectedOutputDiagnosticError.Conflict(identity)
      metadataValidator.validate(record.metadata)
      return record.metadata
    }
    cleanup()
    if (request.truncated && request.rawResponsePath == null) {
      throw RejectedOutputDiagnosticError.Persistence("record-incomplete-capture")
    }
    val payloadPath = request.rawResponsePath?.let(Path::of)
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
      lifecycle = RejectedOutputLifecycle.STORED,
    )
    metadataValidator.validate(metadata)
    applyRestrictivePermissions()
    val record = RejectedOutputDiagnosticRecord(metadata, request.rawResponse.takeUnless { request.truncated })
    return payloadPath?.let { repository.filePayloads.insert(record, it) }?.metadata
      ?: repository.insert(record).metadata
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    applyRestrictivePermissions()
    cleanup()
    repository.producerOutputs.retain(evidence)
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence, payloadPath: Path) {
    applyRestrictivePermissions()
    cleanup()
    repository.producerOutputs.retain(evidence, payloadPath)
  }

  private fun applyRestrictivePermissions() {
    try {
      permissions.applyRestrictivePermissions()
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: IOException) {
      permissionFailure(error)
    } catch (error: SecurityException) {
      permissionFailure(error)
    } catch (error: UnsupportedOperationException) {
      permissionFailure(error)
    }
  }

  fun inspect(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    repository.select(validate(selector).also { cleanup() }).onEach(metadataValidator::validate)

  fun streamRaw(identity: String, output: OutputStream, offset: Long = 0, length: Long? = null): Long {
    if (offset < 0) throw RejectedOutputDiagnosticError.InvalidRequest("offset must be non-negative")
    if (length != null && length < 0) {
      throw RejectedOutputDiagnosticError.InvalidRequest("length must be non-negative when present")
    }
    cleanup()
    val preliminaryMetadata = repository.payloadReader.metadata(identity)
    metadataValidator.validate(preliminaryMetadata)
    ensureReadable(preliminaryMetadata)
    val read = repository.payloadReader.stream(identity, offset, length, output)
    metadataValidator.validate(read.metadata)
    ensureReadable(read.metadata)
    return read.byteCount
  }

  fun cleanup(now: Instant = clock.instant()): Int {
    val cutoff = now.minus(config.retention)
    return repository.markExpired(cutoff) + repository.producerOutputs.deleteBefore(cutoff)
  }

  fun delete(selector: RejectedOutputDiagnosticSelector): Int = repository.delete(validate(selector))

  private fun validate(request: RejectedOutputDiagnosticRequest) {
    requestValidationIssue(request)?.let { reason ->
      throw RejectedOutputDiagnosticError.InvalidRequest(reason)
    }
  }

  private fun validate(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticSelector {
    val issue = when {
      selector.workflowId.isBlank() -> "workflowId must be non-blank"
      selector.phaseId?.isBlank() == true -> "phaseId must be non-blank when present"
      selector.attempt?.let { it <= 0 } == true -> "attempt must be positive when present"
      else -> null
    }
    if (issue != null) throw RejectedOutputDiagnosticError.InvalidRequest(issue)
    return selector
  }

  companion object {
    fun stableIdentity(workflowId: String, phaseId: String, attempt: Int): String =
      "rod_${sha256("$workflowId\u0000$phaseId\u0000$attempt".encodeToByteArray())}"

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

private fun RejectedOutputDiagnosticRepository.readOrNull(identity: String): RejectedOutputDiagnosticRecord? = try {
  read(identity)
} catch (_: RejectedOutputDiagnosticError.Absent) {
  null
}

private fun requestValidationIssue(request: RejectedOutputDiagnosticRequest): String? {
  val required = mapOf(
    "workflowId" to request.workflowId,
    "phaseId" to request.phaseId,
    "rule" to request.rule,
    "path" to request.path,
    "reason" to request.reason,
    "agentId" to request.agentId,
    "model" to request.model,
  )
  val blankField = required.entries.firstOrNull { it.value.isBlank() }?.key
  return when {
    blankField != null -> "$blankField must be non-blank"
    request.attempt <= 0 -> "attempt must be positive"
    request.observedByteSize < request.rawResponse.size || request.observedByteSize < 0 ->
      "observedByteSize must include all retained bytes"
    !Regex("[0-9a-f]{64}").matches(request.observedSha256) ->
      "observedSha256 must be a lowercase SHA-256 digest"
    !request.truncated &&
      (
        request.observedByteSize != request.rawResponse.size.toLong() ||
          request.observedSha256 != RejectedOutputDiagnosticService.sha256(request.rawResponse)
        )
    -> "complete response evidence does not match its bytes"
    else -> null
  }
}

private fun ensureReadable(metadata: RejectedOutputDiagnostic) {
  when (metadata.lifecycle) {
    RejectedOutputLifecycle.EXPIRED -> throw RejectedOutputDiagnosticError.Expired(metadata.identity)
    RejectedOutputLifecycle.OVERSIZED -> throw RejectedOutputDiagnosticError.Oversized(metadata.identity)
    RejectedOutputLifecycle.STORED -> Unit
  }
}

private fun permissionFailure(error: Throwable): Nothing =
  throw RejectedOutputDiagnosticError.Permission("apply", error)

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
