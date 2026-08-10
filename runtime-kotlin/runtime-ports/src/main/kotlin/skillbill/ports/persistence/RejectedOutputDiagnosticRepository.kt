package skillbill.ports.persistence

import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.time.Instant

typealias RejectedOutputLifecycle = skillbill.ports.persistence.model.RejectedOutputLifecycle
typealias RejectedOutputDiagnostic = skillbill.ports.persistence.model.RejectedOutputDiagnostic
typealias RejectedOutputDiagnosticSelector = skillbill.ports.persistence.model.RejectedOutputDiagnosticSelector
typealias RejectedOutputDiagnosticRecord = skillbill.ports.persistence.model.RejectedOutputDiagnosticRecord
typealias ProducerOutputEvidence = skillbill.ports.persistence.model.ProducerOutputEvidence

interface RejectedOutputDiagnosticRepository {
  fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord
  fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic>
  fun read(identity: String): RejectedOutputDiagnosticRecord
  fun markExpired(before: Instant): Int
  fun delete(selector: RejectedOutputDiagnosticSelector): Int
  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    throw RejectedOutputDiagnosticError.Persistence("producer-evidence-unavailable")
  }
  fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int = 0,
  ): ProducerOutputEvidence? = null
  fun deleteProducerOutputsBefore(before: Instant): Int = 0
}

fun interface RejectedOutputDiagnosticMetadataValidator {
  fun validate(metadata: RejectedOutputDiagnostic)
}

fun interface ProducerOutputEvidenceValidator {
  fun validate(evidence: ProducerOutputEvidence)
}

fun interface RejectedOutputDiagnosticPermissions {
  fun applyRestrictivePermissions()
}
