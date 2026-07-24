package skillbill.application.featuretask

import skillbill.workflow.FeatureTaskRuntimeHandoffEnvelopeValidator

/**
 * Accepts every envelope. Tests that exercise the recorder's persistence behavior rather than its
 * schema gate use this so a synthetic envelope fixture does not have to satisfy the full contract;
 * schema-gate coverage drives the real adapter instead.
 */
object AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator : FeatureTaskRuntimeHandoffEnvelopeValidator {
  override fun validateEnvelope(envelope: Map<String, Any?>, workflowId: String?) = Unit
}
