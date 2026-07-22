package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

/**
 * Bridges the domain-owned [FeatureTaskRuntimePhaseOutputValidator] port to the
 * concrete [FeatureTaskRuntimePhaseOutputSchemaValidator].
 */
@Inject
class FeatureTaskRuntimePhaseOutputValidatorAdapter : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(phaseOutputText, sourceLabel)
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> =
    FeatureTaskRuntimePhaseOutputSchemaValidator.validateAndReadPhaseOutput(phaseOutputText, sourceLabel)

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutputSchemaValidator.normalizePhaseOutput(phaseOutputText, sourceLabel)
}
