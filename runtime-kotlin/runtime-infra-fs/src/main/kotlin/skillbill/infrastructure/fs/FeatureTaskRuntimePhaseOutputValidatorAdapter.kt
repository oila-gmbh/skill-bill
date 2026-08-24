package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FeatureTaskRuntimeBuildReceiptSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepairDecision
import skillbill.error.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
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
      // A leniently-verified phase settles through output-verification, not the schema gate, so a
      // structural rejection is not the last word: retry the original text with the lenient reader,
      // which tolerates the extra keys and schema polish this phase is meant to accept. Only a
      // rejection is retried — accepted repairs keep their evidence — so this can turn a block into
      // an acceptance but never the reverse.
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected ->
        leniently(phaseOutputText, sourceLabel)?.let { normalized ->
          FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(
            normalizedOutput = normalized,
            demotedViolations = FeatureTaskRuntimePhaseOutputSchemaValidator.demotedViolations(normalized.envelope) +
              structuralRepairViolation(decision),
          )
        }
          ?: FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
            code = decision.code,
            reason = decision.reason,
            sourceLocation = decision.sourceLocation,
          )

      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted -> try {
        val normalized = if (sourceLabel in LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA) {
          FeatureTaskRuntimePhaseOutputSchemaValidator.normalizeVerifyingPhaseOutputLenient(
            decision.text,
            sourceLabel,
          )
        } else {
          FeatureTaskRuntimePhaseOutputSchemaValidator.normalizePhaseOutput(
            decision.text,
            sourceLabel,
          )
        }
        validateNestedBuildReceipt(normalized, sourceLabel)
        val demoted = FeatureTaskRuntimePhaseOutputSchemaValidator.demotedViolations(
          normalized.envelope,
        )
        if (decision.evidence == null) {
          FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(
            normalizedOutput = normalized,
            demotedViolations = demoted,
          )
        } else {
          FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
            normalizedOutput = normalized,
            evidence = decision.evidence,
            demotedViolations = demoted + repairEvidenceViolation(decision.evidence),
          )
        }
      } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
          code = FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode),
          reason = error.payloadFreeReason ?: "Phase output failed the phase-specific schema contract.",
          diagnosticReason = error.reason,
          payloadFreeReason = error.payloadFreeReason,
          structuralRepairEvidence = decision.evidence,
        )
      } catch (error: InvalidFeatureTaskRuntimeBuildReceiptSchemaError) {
        FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
          code = FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode),
          reason = error.payloadFreeReason ?: "Build receipt failed the build-receipt schema contract.",
          diagnosticReason = error.reason,
          payloadFreeReason = error.payloadFreeReason,
          structuralRepairEvidence = decision.evidence,
        )
      }
    }
  }

  /**
   * The lenient normalization of a phase output the structural gate rejected, or null when it does
   * not settle either. Null keeps the original rejection; it never invents an acceptance.
   */
  private fun leniently(phaseOutputText: String, sourceLabel: String): NormalizedFeatureTaskRuntimePhaseOutput? {
    return try {
      val normalized = if (sourceLabel in LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA) {
        FeatureTaskRuntimePhaseOutputSchemaValidator.normalizeVerifyingPhaseOutputLenient(
          phaseOutputText,
          sourceLabel,
        )
      } else {
        FeatureTaskRuntimePhaseOutputSchemaValidator.normalizePhaseOutputLenient(phaseOutputText, sourceLabel)
      }
      validateNestedBuildReceipt(normalized, sourceLabel)
      normalized
    } catch (_: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      null
    } catch (_: InvalidFeatureTaskRuntimeBuildReceiptSchemaError) {
      null
    }
  }

  private fun structuralRepairViolation(
    decision: FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected,
  ) = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputDemotedViolation(
      rule = "phase-output-structural-repair",
      pointer = "/",
      reason = decision.reason,
    ),
  )

  private fun repairEvidenceViolation(
    evidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) = evidence?.let {
    listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputDemotedViolation(
        rule = "phase-output-structural-repair",
        pointer = "/",
        reason = "Structural repair applied: ${it.operation.wireValue}.",
      ),
    )
  }.orEmpty()

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

  private fun validateNestedBuildReceipt(normalized: NormalizedFeatureTaskRuntimePhaseOutput, sourceLabel: String) {
    if (sourceLabel != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) return
    val produced = JsonSupport.anyToStringAnyMap(normalized.envelope["produced_outputs"])
      ?: throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs must be present for the build phase envelope.",
        payloadFreeReason = "produced_outputs must be present for the build phase envelope.",
      )
    val buildReceipt = JsonSupport.anyToStringAnyMap(produced["build_receipt"])
      ?: throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs.build_receipt is required for the build phase envelope.",
        payloadFreeReason = "produced_outputs.build_receipt is required for the build phase envelope.",
      )
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel)
  }

  private companion object {
    val LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA: Set<String> = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
    )
  }
}
