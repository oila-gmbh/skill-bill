package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator

/**
 * Bridges the domain-owned [FeatureTaskRuntimePhaseOutputValidator] port to the
 * concrete [FeatureTaskRuntimePhaseOutputSchemaValidator].
 */
@Inject
class FeatureTaskRuntimePhaseOutputValidatorAdapter : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(phaseOutputText, sourceLabel)
  }
}
