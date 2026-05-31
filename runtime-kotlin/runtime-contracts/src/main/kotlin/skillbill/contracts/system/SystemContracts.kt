package skillbill.contracts.system

import skillbill.contracts.JsonPayloadContract

data class VersionContract(
  val version: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf("version" to version)
}

data class RuntimeProvenanceContract(
  val executablePath: String,
  val version: String,
  val buildId: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "executable_path" to executablePath,
    "version" to version,
    "build_id" to buildId,
  )
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

fun VersionContract.toRuntimeProvenance(executablePath: String, buildId: String = version): RuntimeProvenanceContract =
  RuntimeProvenanceContract(
    executablePath = executablePath,
    version = version,
    buildId = buildId,
  )
