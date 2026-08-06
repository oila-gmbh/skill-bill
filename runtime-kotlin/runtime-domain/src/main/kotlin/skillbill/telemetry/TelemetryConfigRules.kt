package skillbill.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument

fun defaultLocalTelemetryConfig(installId: String): TelemetryConfigDocument = TelemetryConfigDocument(
  payload =
  mapOf(
    "install_id" to installId,
    "telemetry" to
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
      ),
  ),
)

/**
 * The single rule for recording a telemetry level in the local config document: replace only the
 * `telemetry.level` entry, drop the legacy `enabled` flag, and leave every other payload key
 * (`install_id`, `external_addon_sources`, `execution_matrix`, operator extensions) untouched.
 * The CLI mutation path and the install-apply path both go through here so `off` can never mean
 * "delete the file" on one path and "write level off" on the other.
 */
fun TelemetryConfigDocument.withTelemetryLevel(level: String, configPath: String): TelemetryConfigDocument {
  val updatedPayload = payload.toMutableMap()
  val telemetry =
    when (val telemetryRaw = updatedPayload["telemetry"]) {
      null -> mutableMapOf<String, Any?>()
      is Map<*, *> ->
        telemetryRaw.entries
          .filter { it.key is String }
          .associate { it.key as String to it.value }
          .toMutableMap()
      else -> throw IllegalArgumentException(
        "Telemetry config at '$configPath' must contain a 'telemetry' object.",
      )
    }
  telemetry["level"] = level
  telemetry.remove("enabled")
  updatedPayload["telemetry"] = telemetry
  return TelemetryConfigDocument(updatedPayload)
}

fun parseTelemetryBoolValue(rawValue: String, name: String): Boolean = when (rawValue.trim().lowercase()) {
  "1", "true", "yes", "on" -> true
  "0", "false", "no", "off" -> false
  else -> throw IllegalArgumentException("$name must be one of: 1, 0, true, false, yes, no, on, off.")
}

fun parsePositiveTelemetryInt(rawValue: String, name: String): Int {
  val value = rawValue.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
  require(value > 0) { "$name must be greater than zero." }
  return value
}

fun parseTelemetryLevelValue(rawValue: String, name: String): String {
  val normalized = rawValue.trim().lowercase()
  require(normalized in telemetryLevels) {
    "$name must be one of: ${telemetryLevels.joinToString(", ")}."
  }
  return normalized
}
