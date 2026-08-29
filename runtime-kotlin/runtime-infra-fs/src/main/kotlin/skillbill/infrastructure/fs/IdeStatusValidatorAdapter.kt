package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.IdeStatusSchemaValidator
import skillbill.workflow.idestatus.IdeStatusValidator

@Inject
class IdeStatusValidatorAdapter : IdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
    IdeStatusSchemaValidator.validate(snapshot, sourceLabel)
  }
}
