package skillbill.cli.scaffold

import skillbill.error.InvalidScaffoldPayloadError

/**
 * SKILL-52.2 subtask 2: shared generic raw-map parse helpers for the CLI scaffold parser. These
 * file-private helpers live in a sibling file so the main parser stays under the detekt
 * `TooManyFunctions` threshold; baseline-layer specific helpers live in
 * `ScaffoldCommandRequestBaselineLayerParser.kt`.
 */

internal fun parseStringList(payload: Map<String, Any?>, key: String): List<String> {
  val raw = payload[key]
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list of strings.",
    )
  if (raw !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, key)
}

internal fun parseStringListOrEmpty(payload: Map<String, Any?>, key: String): List<String> {
  val raw = payload[key] ?: return emptyList()
  if (raw !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, key)
}

/**
 * Reads an optional routing-signal list under [key] from the `routing_signals` payload object.
 * Returns null only when the key is absent (so preset fallback still applies); when the key is
 * present but the value is not a list, loud-fails with [InvalidScaffoldPayloadError] mirroring
 * the legacy `requireStringListPayload` error wording.
 */
internal fun parseRoutingSignalList(routing: Map<*, *>, key: String, fieldName: String): List<String>? {
  if (!routing.containsKey(key)) return null
  val raw = routing[key]
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must be a list of strings.",
    )
  if (raw !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must be a list of strings.",
    )
  }
  return parseStringListValue(raw, fieldName)
}

internal fun parseStringListValue(raw: List<*>, fieldName: String): List<String> {
  val mapped = raw.map { value ->
    value as? String
      ?: throw InvalidScaffoldPayloadError(
        "Scaffold payload field '$fieldName' must contain only non-empty strings.",
      )
  }
  if (mapped.any(String::isBlank)) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must contain only non-empty strings.",
    )
  }
  return mapped
}

internal fun parseBooleanOrFalse(payload: Map<String, Any?>, key: String): Boolean {
  if (!payload.containsKey(key)) return false
  return payload[key] as? Boolean
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a boolean when provided.",
    )
}

internal fun requireOptionalNonBlank(payload: Map<String, Any?>, key: String): String? {
  if (!payload.containsKey(key)) return null
  val value = payload[key] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string when provided.",
    )
  if (value.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string when provided.",
    )
  }
  return value
}

internal fun requireStringInMap(layer: Map<*, *>, fieldLabel: String, key: String): String {
  val value = layer[key] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldLabel' must be a non-empty string.",
    )
  if (value.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldLabel' must be a non-empty string.",
    )
  }
  return value
}
