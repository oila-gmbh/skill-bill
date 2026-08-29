package skillbill.application.diagnostics

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticConfig
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class RejectedOutputDiagnosticService(
  private val repository: RejectedOutputDiagnosticRepository,
  private val permissions: RejectedOutputDiagnosticPermissions,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val config: RejectedOutputDiagnosticConfig = RejectedOutputDiagnosticConfig(),
  private val clock: Clock = Clock.systemUTC(),
  private val producerEvidenceValidator: ProducerOutputEvidenceValidator = { },
) {
  fun record(request: RejectedOutputDiagnosticRequest): RejectedOutputDiagnostic {
    validate(request)
    val identity = stableIdentity(request.workflowId, request.phaseId, request.attempt, request.repairTurn)
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
      repairTurn = request.repairTurn,
    )
    metadataValidator.validate(metadata)
    applyRestrictivePermissions()
    return repository.insert(
      RejectedOutputDiagnosticRecord(metadata, request.rawResponse.takeUnless { oversized }),
    ).metadata
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    producerEvidenceValidator.validate(evidence)
    applyRestrictivePermissions()
    cleanup()
    repository.retainProducerOutput(evidence)
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

  fun readRaw(identity: String): ByteArray {
    cleanup()
    val record = repository.read(identity)
    metadataValidator.validate(record.metadata)
    ensureReadable(record.metadata)
    return verifiedPayload(record)
  }

  fun cleanup(now: Instant = clock.instant()): Int {
    val cutoff = now.minus(config.retention)
    return repository.markExpired(cutoff) + repository.deleteProducerOutputsBefore(cutoff)
  }

  fun delete(selector: RejectedOutputDiagnosticSelector): Int = repository.delete(validate(selector))

  private fun validate(request: RejectedOutputDiagnosticRequest) {
    requestValidationIssue(request)?.let { reason ->
      throw RejectedOutputDiagnosticError.InvalidRequest(reason)
    }
  }

  private fun existing(identity: String): RejectedOutputDiagnosticRecord? = try {
    repository.read(identity)
  } catch (_: RejectedOutputDiagnosticError.Absent) {
    null
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
    // SKILL-152: this identity stays generation-blind. It is stored durably on quarantine entries as
    // `diagnosticIdentity`, so adding the review generation would rewrite already-persisted identities.
    // A review-generation restart can therefore still collide here; that widening is tracked separately.
    //
    // SKILL-185: the repair-turn ordinal is folded into the preimage only when it is non-zero. A gate
    // repair cycle re-runs an agent under one unchanged attempt, so two consecutively rejected turns
    // need distinct identities; every ordinary attempt stays at turn 0 and therefore keeps hashing the
    // exact preimage it always did, which is what keeps an identity already persisted on a quarantine
    // entry resolvable.
    fun stableIdentity(workflowId: String, phaseId: String, attempt: Int, repairTurn: Int = 0): String {
      val base = "$workflowId\u0000$phaseId\u0000$attempt"
      val preimage = if (repairTurn == 0) base else "$base\u0000$repairTurn"
      return "rod_${sha256(preimage.encodeToByteArray())}"
    }

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

private fun verifiedPayload(record: RejectedOutputDiagnosticRecord): ByteArray {
  val payload = record.payload ?: throw RejectedOutputDiagnosticError.Corrupt(record.metadata.identity)
  if (payload.size.toLong() != record.metadata.byteSize ||
    RejectedOutputDiagnosticService.sha256(payload) != record.metadata.sha256
  ) {
    throw RejectedOutputDiagnosticError.Corrupt(record.metadata.identity)
  }
  return payload
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
    request.repairTurn < 0 -> "repairTurn must be non-negative"
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
    metadata.repairTurn == request.repairTurn &&
    metadata.rule == request.rule &&
    metadata.path == request.path &&
    metadata.reason == request.reason &&
    metadata.agentId == request.agentId &&
    metadata.model == request.model &&
    metadata.byteSize == request.observedByteSize &&
    metadata.sha256 == request.observedSha256
