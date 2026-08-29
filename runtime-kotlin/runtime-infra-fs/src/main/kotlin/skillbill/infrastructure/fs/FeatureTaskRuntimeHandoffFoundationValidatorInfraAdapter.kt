package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimePersistenceSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseHandoffSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimeProjectionMeasurementSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator

@Inject
class FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter : FeatureTaskRuntimeHandoffFoundationValidator {
  override fun validateDeclaration(payload: Map<String, Any?>, sourceLabel: String) =
    FeatureTaskRuntimePhaseHandoffSchemaValidator.validate(payload, sourceLabel)

  override fun validatePersistenceRecord(payload: Map<String, Any?>, sourceLabel: String) =
    FeatureTaskRuntimePersistenceSchemaValidator.validate(payload, sourceLabel)

  override fun validateMeasurement(payload: Map<String, Any?>, sourceLabel: String) =
    FeatureTaskRuntimeProjectionMeasurementSchemaValidator.validate(payload, sourceLabel)

  override fun validateSharedEvidenceProjection(payload: Map<String, Any?>, sourceLabel: String) =
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(payload, sourceLabel)
}
