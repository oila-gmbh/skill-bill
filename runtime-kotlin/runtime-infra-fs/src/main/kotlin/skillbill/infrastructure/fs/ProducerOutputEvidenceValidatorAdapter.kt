package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.contracts.workflow.ProducerOutputEvidenceSchemaValidator
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.ProducerOutputEvidenceValidator

@Inject
class ProducerOutputEvidenceValidatorAdapter : ProducerOutputEvidenceValidator {
  override fun validate(evidence: ProducerOutputEvidence) {
    ProducerOutputEvidenceSchemaValidator.validate(evidence)
  }
}
