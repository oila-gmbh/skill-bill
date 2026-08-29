package skillbill.ports.diagnostics

import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import java.time.Instant
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle

typealias RejectedOutputLifecycle = RejectedOutputLifecycle
typealias RejectedOutputDiagnostic = RejectedOutputDiagnostic
typealias RejectedOutputDiagnosticSelector = RejectedOutputDiagnosticSelector
typealias RejectedOutputDiagnosticRecord = RejectedOutputDiagnosticRecord
typealias ProducerOutputEvidence = ProducerOutputEvidence

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
