package skillbill.telemetry

import java.util.UUID

fun defaultLocalTelemetryConfig(): Map<String, Any?> = mapOf(
  "install_id" to UUID.randomUUID().toString(),
  "telemetry" to
    mapOf(
      "level" to "anonymous",
      "proxy_url" to "",
      "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
    ),
)

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
