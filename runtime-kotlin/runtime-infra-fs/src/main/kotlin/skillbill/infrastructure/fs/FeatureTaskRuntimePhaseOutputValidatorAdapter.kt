package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepairDecision
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAccepted

/**
 * Bridges the domain-owned [FeatureTaskRuntimePhaseOutputValidator] port to the
 * concrete [FeatureTaskRuntimePhaseOutputSchemaValidator].
 */
@Inject
class FeatureTaskRuntimePhaseOutputValidatorAdapter : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    val decision = FeatureTaskRuntimePhaseOutputStructuralRepair.inspect(phaseOutputText, sourceLabel)
    return when (decision) {
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected ->
        FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
          code = decision.code,
          reason = decision.reason,
          sourceLocation = decision.sourceLocation,
        )

      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted -> try {
        val normalized = FeatureTaskRuntimePhaseOutputSchemaValidator.normalizePhaseOutput(
          decision.text,
          sourceLabel,
        )
        if (decision.evidence == null) {
          FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(normalized)
        } else {
          FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(normalized, decision.evidence)
        }
      } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        // Syntax repair may have accepted the capture; keep digest/location evidence on Rejected so
        // the corrective retry can mark acceptedAfterStructuralRepair without claiming schema accept.
        FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
          code = FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode),
          reason = error.payloadFreeReason ?: "Phase output failed the phase-specific schema contract.",
          diagnosticReason = error.reason,
          payloadFreeReason = error.payloadFreeReason,
          structuralRepairEvidence = decision.evidence,
        )
      }
    }
  }

  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> =
    validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel).envelope

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)
}
