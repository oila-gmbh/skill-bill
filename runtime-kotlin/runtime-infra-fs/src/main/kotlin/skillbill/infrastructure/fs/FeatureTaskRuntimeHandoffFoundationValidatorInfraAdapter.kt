package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.contracts.workflow.FeatureTaskRuntimePersistenceSchemaValidator
import skillbill.infrastructure.fs.contracts.workflow.FeatureTaskRuntimePhaseHandoffSchemaValidator
import skillbill.infrastructure.fs.contracts.workflow.FeatureTaskRuntimeProjectionMeasurementSchemaValidator
import skillbill.infrastructure.fs.contracts.workflow.FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
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
