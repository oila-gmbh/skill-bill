package skillbill.application.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator

object ReviewRawOutputFallbackValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    if (JsonCodec.parseObjectOrNull(phaseOutputText) == null) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "must be a JSON object when no runtime schema validator is injected.",
      )
    }
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    validatePhaseOutputText(phaseOutputText, sourceLabel)
    return requireNotNull(JsonCodec.parseObjectOrNull(phaseOutputText))
      .let(JsonCodec::jsonElementToValue)
      .let(JsonCodec::anyToStringAnyMap)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "must decode to a string-keyed object when no runtime schema validator is injected.",
      )
  }
}
