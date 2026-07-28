package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.RejectedOutputDiagnosticSchemaValidator
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator

@Inject
class RejectedOutputDiagnosticMetadataValidatorAdapter : RejectedOutputDiagnosticMetadataValidator {
  override fun validate(metadata: RejectedOutputDiagnostic) {
    RejectedOutputDiagnosticSchemaValidator.validate(metadata)
  }
}
