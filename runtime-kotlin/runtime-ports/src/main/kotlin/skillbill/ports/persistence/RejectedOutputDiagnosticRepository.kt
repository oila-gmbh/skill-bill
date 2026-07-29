package skillbill.ports.persistence

import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.ports.persistence.model.RejectedOutputPayloadRead
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant

typealias RejectedOutputLifecycle = skillbill.ports.persistence.model.RejectedOutputLifecycle
typealias RejectedOutputDiagnostic = skillbill.ports.persistence.model.RejectedOutputDiagnostic
typealias RejectedOutputDiagnosticSelector = skillbill.ports.persistence.model.RejectedOutputDiagnosticSelector
typealias RejectedOutputDiagnosticRecord = skillbill.ports.persistence.model.RejectedOutputDiagnosticRecord
typealias ProducerOutputEvidence = skillbill.ports.persistence.model.ProducerOutputEvidence

interface RejectedOutputDiagnosticRepository {
  val payloadReader: RejectedOutputPayloadReader
    get() = object : RejectedOutputPayloadReader {
      override fun metadata(identity: String): RejectedOutputDiagnostic = read(identity).metadata

      override fun stream(
        identity: String,
        offset: Long,
        length: Long?,
        output: OutputStream,
      ): RejectedOutputPayloadRead {
        val record = read(identity)
        val payload = record.payload ?: throw RejectedOutputDiagnosticError.Corrupt(identity)
        if (payload.size.toLong() != record.metadata.byteSize ||
          sha256(payload) != record.metadata.sha256
        ) {
          throw RejectedOutputDiagnosticError.Corrupt(identity)
        }
        val start = offset.coerceAtMost(payload.size.toLong()).toInt()
        val count = (length ?: (payload.size - start).toLong())
          .coerceAtMost((payload.size - start).toLong())
          .toInt()
        output.write(payload, start, count)
        return RejectedOutputPayloadRead(record.metadata, count.toLong())
      }
    }
  val filePayloads: RejectedOutputFilePayloadRepository
    get() = UnavailableRejectedOutputFilePayloadRepository
  val producerOutputs: ProducerOutputEvidenceRepository
    get() = UnavailableProducerOutputEvidenceRepository

  fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord
  fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic>
  fun read(identity: String): RejectedOutputDiagnosticRecord
  fun markExpired(before: Instant): Int
  fun delete(selector: RejectedOutputDiagnosticSelector): Int
}

interface RejectedOutputFilePayloadRepository {
  fun insert(record: RejectedOutputDiagnosticRecord, payloadPath: Path): RejectedOutputDiagnosticRecord
  fun release(payloadPath: Path) = Unit
}

interface ProducerOutputEvidenceRepository {
  fun retain(evidence: ProducerOutputEvidence)
  fun retain(evidence: ProducerOutputEvidence, payloadPath: Path) {
    throw RejectedOutputDiagnosticError.Persistence("producer-file-evidence-unavailable")
  }
  fun read(workflowId: String, phaseId: String, attempt: Int): ProducerOutputEvidence? = null
  fun latestAttempt(workflowId: String, phaseId: String): Int = 0
  fun deleteBefore(before: Instant): Int = 0
}

private object UnavailableRejectedOutputFilePayloadRepository : RejectedOutputFilePayloadRepository {
  override fun insert(record: RejectedOutputDiagnosticRecord, payloadPath: Path): RejectedOutputDiagnosticRecord {
    throw RejectedOutputDiagnosticError.Persistence("diagnostic-file-payload-unavailable")
  }
}

private object UnavailableProducerOutputEvidenceRepository : ProducerOutputEvidenceRepository {
  override fun retain(evidence: ProducerOutputEvidence) {
    throw RejectedOutputDiagnosticError.Persistence("producer-evidence-unavailable")
  }
}

private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
  .digest(bytes)
  .joinToString("") { "%02x".format(it) }

interface RejectedOutputPayloadReader {
  fun metadata(identity: String): RejectedOutputDiagnostic
  fun stream(identity: String, offset: Long, length: Long?, output: OutputStream): RejectedOutputPayloadRead
}

fun interface RejectedOutputDiagnosticMetadataValidator {
  fun validate(metadata: RejectedOutputDiagnostic)
}

fun interface RejectedOutputDiagnosticPermissions {
  fun applyRestrictivePermissions()
}
