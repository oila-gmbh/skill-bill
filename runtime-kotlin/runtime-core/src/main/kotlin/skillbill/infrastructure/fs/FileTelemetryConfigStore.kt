package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import skillbill.telemetry.TelemetryConfigRuntime
import skillbill.telemetry.expandAndNormalizeTelemetryPath
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileTelemetryConfigStore(
  private val context: RuntimeContext,
) : TelemetryConfigStore {
  override fun stateDir(): Path = resolveTelemetryStateDir(context.environment, context.userHome)

  override fun configPath(): Path = resolveTelemetryConfigPath(context.environment, context.userHome)

  override fun read(): Map<String, Any?>? = readTelemetryConfigFile(configPath())

  override fun ensure(): Map<String, Any?> = ensureTelemetryConfigFile(configPath())

  override fun write(payload: Map<String, Any?>) = writeTelemetryConfigFile(configPath(), payload)

  override fun delete(): Boolean = Files.deleteIfExists(configPath())
}

internal fun resolveTelemetryStateDir(
  environment: Map<String, String> = System.getenv(),
  userHome: Path = Path.of(System.getProperty("user.home")),
): Path = environment[STATE_DIR_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
  expandAndNormalizeTelemetryPath(it, userHome)
} ?: userHome.resolve(".skill-bill").toAbsolutePath().normalize()

internal fun resolveTelemetryConfigPath(
  environment: Map<String, String> = System.getenv(),
  userHome: Path = Path.of(System.getProperty("user.home")),
): Path = environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
  expandAndNormalizeTelemetryPath(it, userHome)
} ?: userHome.resolve(".skill-bill").resolve("config.json").toAbsolutePath().normalize()

internal fun readTelemetryConfigFile(path: Path): Map<String, Any?>? {
  if (!Files.exists(path)) {
    return null
  }
  val rawPayload = JsonSupport.parseObjectOrNull(Files.readString(path))
    ?: throw IllegalArgumentException("Telemetry config at '$path' is not valid JSON.")
  return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(rawPayload))
    ?: throw IllegalArgumentException("Telemetry config at '$path' must contain a JSON object.")
}

internal fun ensureTelemetryConfigFile(path: Path): Map<String, Any?> {
  path.parent?.let(Files::createDirectories)
  val existing = readTelemetryConfigFile(path)
  val payload = (existing?.toMutableMap() ?: mutableMapOf())
  val defaults = TelemetryConfigRuntime.defaultLocalConfig()
  val telemetry = normalizedTelemetryMap(payload, defaults)
  payload["install_id"] = normalizedInstallId(payload, defaults)
  payload["telemetry"] = telemetry
  if (!Files.exists(path) || existing != payload) {
    writeTelemetryConfigFile(path, payload)
  }
  return payload
}

internal fun writeTelemetryConfigFile(path: Path, payload: Map<String, Any?>) {
  path.parent?.let(Files::createDirectories)
  Files.writeString(path, JsonSupport.mapToJsonString(payload) + "\n")
}

private fun normalizedInstallId(payload: MutableMap<String, Any?>, defaults: Map<String, Any?>): String =
  (payload["install_id"] as? String)?.takeIf(String::isNotBlank)
    ?: defaults.getValue("install_id").toString()

private fun normalizedTelemetryMap(payload: MutableMap<String, Any?>, defaults: Map<String, Any?>): Map<String, Any?> {
  val telemetryRaw = payload["telemetry"]
  val telemetry =
    (telemetryRaw as? Map<*, *>)
      ?.entries
      ?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?.toMutableMap() ?: mutableMapOf()
  val defaultTelemetry = defaults.getValue("telemetry") as Map<*, *>
  normalizeLegacyEnabledFlag(telemetry)
  listOf("level", "proxy_url", "batch_size").forEach { key ->
    telemetry.putIfAbsent(key, defaultTelemetry[key])
  }
  return telemetry
}

private fun normalizeLegacyEnabledFlag(telemetry: MutableMap<String, Any?>) {
  if (!telemetry.containsKey("level") && telemetry.containsKey("enabled")) {
    val enabledRaw = telemetry.remove("enabled")
    telemetry["level"] = legacyEnabledLevel(enabledRaw)
  } else {
    telemetry.remove("enabled")
  }
}

private fun legacyEnabledLevel(enabledRaw: Any?): String = when (enabledRaw) {
  is Boolean -> if (enabledRaw) "anonymous" else "off"
  is String -> if (TelemetryConfigRuntime.parseBoolValue(enabledRaw, "telemetry.enabled")) "anonymous" else "off"
  else -> if (enabledRaw == true) "anonymous" else "off"
}
