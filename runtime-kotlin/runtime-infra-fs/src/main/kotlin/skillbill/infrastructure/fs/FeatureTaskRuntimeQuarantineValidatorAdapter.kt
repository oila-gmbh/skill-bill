package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimeQuarantineSchemaValidator
import skillbill.workflow.FeatureTaskRuntimeQuarantineValidator

/**
 * Bridges the domain-owned [FeatureTaskRuntimeQuarantineValidator] port to the concrete
 * [FeatureTaskRuntimeQuarantineSchemaValidator].
 */
@Inject
class FeatureTaskRuntimeQuarantineValidatorAdapter : FeatureTaskRuntimeQuarantineValidator {
  override fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String) {
    FeatureTaskRuntimeQuarantineSchemaValidator.validate(quarantineRecord, sourceLabel)
  }
}
