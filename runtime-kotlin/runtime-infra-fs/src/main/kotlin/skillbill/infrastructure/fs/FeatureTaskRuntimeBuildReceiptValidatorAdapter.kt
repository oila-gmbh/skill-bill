package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.contracts.workflow.FeatureTaskRuntimeBuildReceiptSchemaValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator

@Inject
class FeatureTaskRuntimeBuildReceiptValidatorAdapter : FeatureTaskRuntimeBuildReceiptValidator {
  override fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String) {
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel)
  }
}
