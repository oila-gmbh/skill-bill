package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimeImplementationAttemptSchemaValidator
import skillbill.workflow.FeatureTaskRuntimeImplementationAttemptValidator

/**
 * Bridges the domain-owned [FeatureTaskRuntimeImplementationAttemptValidator] port to the concrete
 * [FeatureTaskRuntimeImplementationAttemptSchemaValidator].
 */
@Inject
class FeatureTaskRuntimeImplementationAttemptValidatorAdapter : FeatureTaskRuntimeImplementationAttemptValidator {
  override fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String) {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(attemptRecord, sourceLabel)
  }
}
