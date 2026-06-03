package skillbill.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError

/**
 * Domain port for validating a phase's output payload; the concrete JSON-Schema
 * validator lives in `runtime-infra-fs`. Accepting JSON/YAML text keeps
 * `runtime-domain` free of Jackson and avoids a raw-map boundary surface.
 */
interface FeatureTaskRuntimePhaseOutputValidator {
  /**
   * Parses [phaseOutputText] (JSON or YAML) and validates it against the canonical
   * per-phase output schema. Throws [InvalidFeatureTaskRuntimePhaseOutputSchemaError]
   * on malformed input, a non-object root, empty `{}`, or any schema violation.
   * [sourceLabel] (typically the phase id) is woven into the failure message.
   */
  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String)
}
