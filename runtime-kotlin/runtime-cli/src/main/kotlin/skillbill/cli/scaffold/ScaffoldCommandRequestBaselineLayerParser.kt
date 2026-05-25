package skillbill.cli.scaffold

import skillbill.contracts.scaffold.wire.optionalList
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope

/**
 * SKILL-52.2 subtask 2: per-kind baseline-layer parser for the CLI scaffold parser. Split out of
 * the main parser file so each function stays under the detekt `ThrowsCount` threshold and the
 * generic helpers file stays under the `TooManyFunctions` threshold.
 */
internal fun parseBaselineLayers(payload: Map<String, Any?>): List<CodeReviewBaselineLayer> {
  val raw = optionalList(payload, "baseline_layers") ?: return emptyList()
  if (raw.isEmpty()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'baseline_layers' must contain at least one layer when provided.",
    )
  }
  return raw.mapIndexed(::parseBaselineLayer)
}

private fun parseBaselineLayer(index: Int, entry: Any?): CodeReviewBaselineLayer {
  val layer = entry as? Map<*, *>
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'baseline_layers[$index]' must be an object.",
    )
  val fieldPrefix = "baseline_layers[$index]"
  val scopeValue = requireStringInMap(layer, "$fieldPrefix.scope", "scope")
  val modeValue = requireStringInMap(layer, "$fieldPrefix.mode", "mode")
  return CodeReviewBaselineLayer(
    platform = requireStringInMap(layer, "$fieldPrefix.platform", "platform"),
    skill = requireStringInMap(layer, "$fieldPrefix.skill", "skill"),
    scope = parseBaselineScope(scopeValue, fieldPrefix),
    required = parseRequiredFlag(layer, fieldPrefix),
    mode = parseBaselineMode(modeValue, fieldPrefix),
  )
}

private fun parseRequiredFlag(layer: Map<*, *>, fieldPrefix: String): Boolean = layer["required"] as? Boolean
  ?: throw InvalidScaffoldPayloadError(
    "Scaffold payload field '$fieldPrefix.required' must be an explicit boolean.",
  )

private fun parseBaselineScope(scopeValue: String, fieldPrefix: String): CodeReviewCompositionScope =
  CodeReviewCompositionScope.fromWireValue(scopeValue)
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldPrefix.scope' has unsupported value '$scopeValue'. " +
        "Supported values: ${CodeReviewCompositionScope.entries.map { it.wireValue }}.",
    )

private fun parseBaselineMode(modeValue: String, fieldPrefix: String): CodeReviewCompositionMode =
  CodeReviewCompositionMode.fromWireValue(modeValue)
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldPrefix.mode' has unsupported value '$modeValue'. " +
        "Supported values: ${CodeReviewCompositionMode.entries.map { it.wireValue }}.",
    )
