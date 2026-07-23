package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimePlanningProjectionSchemaValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator

/**
 * Bridges the domain-owned [FeatureTaskRuntimePlanningProjectionValidator] port to the concrete
 * [FeatureTaskRuntimePlanningProjectionSchemaValidator].
 */
@Inject
class FeatureTaskRuntimePlanningProjectionValidatorAdapter : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) {
    FeatureTaskRuntimePlanningProjectionSchemaValidator.validate(producedOutputs, sourceLabel)
  }
}
