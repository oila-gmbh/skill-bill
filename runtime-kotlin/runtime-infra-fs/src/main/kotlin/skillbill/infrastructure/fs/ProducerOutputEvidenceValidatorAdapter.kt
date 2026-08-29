package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.contracts.workflow.ProducerOutputEvidenceSchemaValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator

@Inject
class ProducerOutputEvidenceValidatorAdapter : ProducerOutputEvidenceValidator {
  override fun validate(evidence: ProducerOutputEvidence) {
    ProducerOutputEvidenceSchemaValidator.validate(evidence)
  }
}
