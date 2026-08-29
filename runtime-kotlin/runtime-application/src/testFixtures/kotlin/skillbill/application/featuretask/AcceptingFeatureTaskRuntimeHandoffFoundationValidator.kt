package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator

object AcceptingFeatureTaskRuntimeHandoffFoundationValidator : FeatureTaskRuntimeHandoffFoundationValidator {
  override fun validateDeclaration(payload: Map<String, Any?>, sourceLabel: String) = Unit
  override fun validatePersistenceRecord(payload: Map<String, Any?>, sourceLabel: String) = Unit
  override fun validateMeasurement(payload: Map<String, Any?>, sourceLabel: String) = Unit
  override fun validateSharedEvidenceProjection(payload: Map<String, Any?>, sourceLabel: String) = Unit
}
