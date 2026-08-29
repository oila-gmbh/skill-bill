package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

/**
 * Domain port for validating a phase's output payload; the concrete JSON-Schema
 * validator lives in `runtime-infra-fs`. Accepting JSON/YAML text keeps
 * `runtime-domain` free of Jackson and avoids a raw-map boundary surface.
 */
interface FeatureTaskRuntimePhaseOutputValidator {
  /**
   * Returns the versioned validation outcome. Infrastructure adapters override this
   * to add structural repair; the default keeps existing test doubles and callers
   * source-compatible while preserving the old normalization seam.
   */
  fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult = try {
    FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(
      normalizePhaseOutput(phaseOutputText, sourceLabel),
    )
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
      code = if (error.failureKind == skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED) {
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED
      } else {
        FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode)
      },
      reason = error.payloadFreeReason ?: "Phase output was rejected by the phase-output contract.",
      diagnosticReason = error.reason,
      payloadFreeReason = error.payloadFreeReason,
    )
  }

  /**
   * Parses [phaseOutputText] (JSON or YAML) and validates it against the canonical
   * per-phase output schema. Throws [InvalidFeatureTaskRuntimePhaseOutputSchemaError]
   * on malformed input, a non-object root, empty `{}`, or any schema violation.
   * [sourceLabel] (typically the phase id) is woven into the failure message.
   */
  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String)

  /**
   * Validates and returns the parsed string-keyed output map. Implementations that can parse the
   * same JSON/YAML surface as the schema gate should override this; the default is sufficient for
   * JSON-only test doubles and preserves the existing validate-only contract for callers that do
   * not need the projection.
   */
  @OpenBoundaryMap("Feature-task-runtime phase-output schema gate returns the validated wire map for typed projection")
  fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    validatePhaseOutputText(phaseOutputText, sourceLabel)
    return skillbill.contracts.JsonSupport.parseObjectOrNull(phaseOutputText)
      ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
      ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
      ?: emptyMap()
  }

  fun normalizePhaseOutput(phaseOutputText: String, sourceLabel: String): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = validateAndReadPhaseOutput(phaseOutputText, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = skillbill.contracts.JsonSupport.mapToJsonString(envelope),
      envelope = envelope,
    )
  }
}
