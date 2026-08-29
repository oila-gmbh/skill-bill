package skillbill.ports.diagnostics

import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import java.time.Instant

typealias RejectedOutputLifecycle = skillbill.ports.diagnostics.model.RejectedOutputLifecycle
typealias RejectedOutputDiagnostic = skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
typealias RejectedOutputDiagnosticSelector = skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
typealias RejectedOutputDiagnosticRecord = skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
typealias ProducerOutputEvidence = skillbill.ports.diagnostics.model.ProducerOutputEvidence

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
