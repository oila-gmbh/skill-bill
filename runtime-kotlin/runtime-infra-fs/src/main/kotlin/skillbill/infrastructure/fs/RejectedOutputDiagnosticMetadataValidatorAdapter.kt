package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.contracts.workflow.RejectedOutputDiagnosticSchemaValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator

@Inject
class RejectedOutputDiagnosticMetadataValidatorAdapter : RejectedOutputDiagnosticMetadataValidator {
  override fun validate(metadata: RejectedOutputDiagnostic) {
    RejectedOutputDiagnosticSchemaValidator.validate(metadata)
  }
}
