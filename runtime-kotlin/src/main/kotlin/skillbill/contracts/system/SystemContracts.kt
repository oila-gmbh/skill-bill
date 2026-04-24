package skillbill.contracts.system

import skillbill.contracts.JsonPayloadContract

data class VersionContract(
  val version: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf("version" to version)
}

data class DoctorContract(
  val version: String,
  val dbPath: String,
  val dbExists: Boolean,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "version" to version,
    "db_path" to dbPath,
    "db_exists" to dbExists,
    "telemetry_enabled" to telemetryEnabled,
    "telemetry_level" to telemetryLevel,
  )
}
