package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope

/**
 * SKILL-52.1 subtask 2: pure-policy half of the scaffold payload parser.
 *
 * Owns the wire-shape validation rules that have no filesystem dependency:
 *  - scaffold_payload_version contract (`validatePayloadVersion`)
 *  - kind discriminator (`detectKind`)
 *  - per-layer baseline-layer payload parsing (`parseBaselineLayerPayload`)
 *
 * The IO-coupled cross-pack baseline reference validation
 * (`validateBaselineLayerPayloadReferences`) remains in `runtime-infra-fs` and is deferred to
 * subtask 3.
 */

/**
 * Asserts the payload declares the expected wire-version. Throws [InvalidScaffoldPayloadError]
 * when the field is missing or [ScaffoldPayloadVersionMismatchError] when it disagrees with
 * [SCAFFOLD_PAYLOAD_VERSION].
 */
fun validatePayloadVersion(payload: Map<String, Any?>) {
  val version = payload["scaffold_payload_version"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload is missing required field 'scaffold_payload_version'.",
    )
  if (version != SCAFFOLD_PAYLOAD_VERSION) {
    throw ScaffoldPayloadVersionMismatchError(
      "Scaffold payload declares 'scaffold_payload_version' '$version' " +
        "but the scaffolder expects '$SCAFFOLD_PAYLOAD_VERSION'.",
    )
  }
}

/**
 * Returns the canonical `kind` discriminator from [payload]. Throws on missing/blank kinds and
 * loud-fails with [UnknownSkillKindError] when the value is outside [SUPPORTED_SKILL_KINDS].
 */
fun detectKind(payload: Map<String, Any?>): String {
  val kind = payload["kind"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'kind' must be a non-empty string.",
    )
  if (kind !in SUPPORTED_SKILL_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. " +
        "Supported kinds: $SUPPORTED_SKILL_KINDS.",
    )
  }
  return kind
}

/**
 * Parses a single `baseline_layers[index]` payload entry into a typed [CodeReviewBaselineLayer].
 * Wire-shape errors throw [InvalidScaffoldPayloadError] with `baseline_layers[N]`-prefixed
 * field labels so the caller-facing diagnostics remain stable.
 *
 * Cross-pack reference validation (target pack exists, declares skill, mode compatibility) is
 * NOT performed here — it lives in `runtime-infra-fs` next to the IO seam.
 */
fun parseBaselineLayerPayload(index: Int, raw: Any?): CodeReviewBaselineLayer {
  val layer = raw as? Map<*, *>
    ?: failBaselineLayerPayload("Scaffold payload field 'baseline_layers[$index]' must be an object.")
  val fieldPrefix = "baseline_layers[$index]"
  val scopeValue = requireStringInPayloadMap(layer, "$fieldPrefix.scope", "scope")
  val modeValue = requireStringInPayloadMap(layer, "$fieldPrefix.mode", "mode")
  val required = layer["required"] as? Boolean
    ?: failBaselineLayerPayload(
      "Scaffold payload field '$fieldPrefix.required' must be an explicit boolean.",
    )
  return CodeReviewBaselineLayer(
    platform = requireStringInPayloadMap(layer, "$fieldPrefix.platform", "platform"),
    skill = requireStringInPayloadMap(layer, "$fieldPrefix.skill", "skill"),
    scope = CodeReviewCompositionScope.fromWireValue(scopeValue)
      ?: failBaselineLayerPayload(
        "Scaffold payload field '$fieldPrefix.scope' has unsupported value '$scopeValue'. " +
          "Supported values: ${CodeReviewCompositionScope.entries.map { it.wireValue }}.",
      ),
    required = required,
    mode = CodeReviewCompositionMode.fromWireValue(modeValue)
      ?: failBaselineLayerPayload(
        "Scaffold payload field '$fieldPrefix.mode' has unsupported value '$modeValue'. " +
          "Supported values: ${CodeReviewCompositionMode.entries.map { it.wireValue }}.",
      ),
  )
}

private fun failBaselineLayerPayload(message: String): Nothing = throw InvalidScaffoldPayloadError(message)
