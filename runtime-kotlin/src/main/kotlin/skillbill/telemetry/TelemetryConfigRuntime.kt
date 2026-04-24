package skillbill.telemetry

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object TelemetryConfigRuntime {
  fun stateDir(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Path = environment[STATE_DIR_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    expandAndNormalizeTelemetryPath(it, userHome)
  } ?: userHome.resolve(".skill-bill").toAbsolutePath().normalize()

  fun resolveConfigPath(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Path = environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    expandAndNormalizeTelemetryPath(it, userHome)
  } ?: userHome.resolve(".skill-bill").resolve("config.json").toAbsolutePath().normalize()

  fun defaultLocalConfig(): Map<String, Any?> = mapOf(
    "install_id" to UUID.randomUUID().toString(),
    "telemetry" to
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
      ),
  )

  fun readLocalConfig(path: Path): Map<String, Any?>? {
    if (!Files.exists(path)) {
      return null
    }
    val rawPayload = JsonSupport.parseObjectOrNull(Files.readString(path))
      ?: throw IllegalArgumentException("Telemetry config at '$path' is not valid JSON.")
    return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(rawPayload))
      ?: throw IllegalArgumentException("Telemetry config at '$path' must contain a JSON object.")
  }

  fun ensureLocalConfig(path: Path): Map<String, Any?> {
    path.parent?.let(Files::createDirectories)
    val payload = (readLocalConfig(path)?.toMutableMap() ?: mutableMapOf())
    val defaults = defaultLocalConfig()
    val telemetry = normalizedTelemetryMap(payload, defaults)
    payload["install_id"] = normalizedInstallId(payload, defaults)
    payload["telemetry"] = telemetry
    if (!Files.exists(path) || readLocalConfig(path) != payload) {
      Files.writeString(path, JsonSupport.mapToJsonString(payload) + "\n")
    }
    return payload
  }

  fun parseBoolValue(rawValue: String, name: String): Boolean = when (rawValue.trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> throw IllegalArgumentException("$name must be one of: 1, 0, true, false, yes, no, on, off.")
  }

  fun parsePositiveInt(rawValue: String, name: String): Int {
    val value = rawValue.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
    require(value > 0) { "$name must be greater than zero." }
    return value
  }

  fun parseTelemetryLevel(rawValue: String, name: String): String {
    val normalized = rawValue.trim().lowercase()
    require(normalized in telemetryLevels) {
      "$name must be one of: ${telemetryLevels.joinToString(", ")}."
    }
    return normalized
  }

  fun loadTelemetrySettings(
    materialize: Boolean = false,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): TelemetrySettings {
    val configPath = resolveConfigPath(environment, userHome)
    val config = if (materialize) ensureLocalConfig(configPath) else readLocalConfig(configPath)
    val baseSettings = configBackedSettings(configPath, config)
    val envSettings = applyEnvironmentOverrides(baseSettings, environment)
    val enabled = envSettings.level != "off"
    val (proxyUrl, customProxyUrl) = telemetryProxyUrl(envSettings.customProxyUrl)
    require(!enabled || envSettings.installId.isNotBlank()) {
      "Telemetry is enabled but no install_id is configured at '$configPath'. " +
        "Run 'skill-bill telemetry enable' to create one."
    }
    return TelemetrySettings(
      configPath = configPath,
      level = envSettings.level,
      enabled = enabled,
      installId = envSettings.installId,
      proxyUrl = proxyUrl,
      customProxyUrl = customProxyUrl,
      batchSize = envSettings.batchSize,
    )
  }

  fun telemetryIsEnabled(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Boolean = runCatching {
    loadTelemetrySettings(environment = environment, userHome = userHome).enabled
  }.getOrDefault(false)
}

private data class MutableTelemetrySettings(
  val level: String,
  val customProxyUrl: String,
  val batchSize: Int,
  val installId: String,
)

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

private fun configBackedSettings(configPath: Path, config: Map<String, Any?>?): MutableTelemetrySettings {
  if (config == null) {
    return MutableTelemetrySettings(
      level = "off",
      customProxyUrl = "",
      batchSize = DEFAULT_TELEMETRY_BATCH_SIZE,
      installId = "",
    )
  }
  val telemetryRaw = config["telemetry"]
  val telemetry =
    when (telemetryRaw) {
      null -> emptyMap()
      is Map<*, *> -> telemetryRaw.entries.filter { it.key is String }.associate { it.key as String to it.value }
      else -> throw IllegalArgumentException("Telemetry config at '$configPath' must contain a 'telemetry' object.")
    }
  return MutableTelemetrySettings(
    level = telemetryLevelFromConfig(telemetry),
    customProxyUrl = telemetry["proxy_url"]?.toString()?.trim().orEmpty(),
    batchSize = telemetryBatchSize(telemetry),
    installId = config["install_id"]?.toString()?.trim().orEmpty(),
  )
}

private fun telemetryLevelFromConfig(telemetry: Map<String, Any?>): String {
  val levelRaw = telemetry["level"]
  val enabledRaw = telemetry["enabled"]
  return when {
    levelRaw != null -> TelemetryConfigRuntime.parseTelemetryLevel(levelRaw.toString(), "telemetry.level")
    enabledRaw is Boolean -> if (enabledRaw) "anonymous" else "off"
    enabledRaw is String ->
      if (TelemetryConfigRuntime.parseBoolValue(enabledRaw, "telemetry.enabled")) {
        "anonymous"
      } else {
        "off"
      }
    enabledRaw != null -> if (enabledRaw == true) "anonymous" else "off"
    else -> "anonymous"
  }
}

private fun telemetryBatchSize(telemetry: Map<String, Any?>): Int = when (val batchSizeRaw = telemetry["batch_size"]) {
  is Int -> batchSizeRaw
  is Number -> batchSizeRaw.toInt()
  null -> DEFAULT_TELEMETRY_BATCH_SIZE
  else -> TelemetryConfigRuntime.parsePositiveInt(batchSizeRaw.toString(), "telemetry.batch_size")
}

private fun applyEnvironmentOverrides(
  settings: MutableTelemetrySettings,
  environment: Map<String, String>,
): MutableTelemetrySettings {
  var level = settings.level
  var customProxyUrl = settings.customProxyUrl
  var batchSize = settings.batchSize
  var installId = settings.installId

  environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    level = TelemetryConfigRuntime.parseTelemetryLevel(it, TELEMETRY_LEVEL_ENVIRONMENT_KEY)
  } ?: environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    level = if (TelemetryConfigRuntime.parseBoolValue(it, TELEMETRY_ENABLED_ENVIRONMENT_KEY)) "anonymous" else "off"
  }
  environment[TELEMETRY_PROXY_URL_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { customProxyUrl = it.trim() }
  environment[INSTALL_ID_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { installId = it.trim() }
  environment[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    batchSize = TelemetryConfigRuntime.parsePositiveInt(it, TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY)
  }
  return MutableTelemetrySettings(
    level = level,
    customProxyUrl = customProxyUrl,
    batchSize = batchSize,
    installId = installId,
  )
}
