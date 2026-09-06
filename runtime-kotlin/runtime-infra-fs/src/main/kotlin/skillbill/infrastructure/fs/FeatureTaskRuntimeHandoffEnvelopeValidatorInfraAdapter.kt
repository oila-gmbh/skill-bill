package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimeHandoffEnvelopeSchemaValidator
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator

/**
 * Bridges the domain-owned [FeatureTaskRuntimeHandoffEnvelopeValidator] port to the concrete
 * [FeatureTaskRuntimeHandoffEnvelopeSchemaValidator].
 */
@Inject
class FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter : FeatureTaskRuntimeHandoffEnvelopeValidator {
  override fun validateEnvelope(envelope: Map<String, Any?>, workflowId: WorkflowId?) {
    FeatureTaskRuntimeHandoffEnvelopeSchemaValidator.validate(envelope, workflowId?.value)
  }
}
