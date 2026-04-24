package skillbill.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider

@Inject
class DefaultTelemetrySettingsProvider(
  private val context: RuntimeContext,
  private val configStore: TelemetryConfigStore,
) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = loadTelemetrySettingsFromStore(
    materialize = materialize,
    environment = context.environment,
    configStore = configStore,
  )
}

internal fun loadTelemetrySettingsFromStore(
  materialize: Boolean,
  environment: Map<String, String>,
  configStore: TelemetryConfigStore,
): TelemetrySettings {
  val configPath = configStore.configPath()
  val config = if (materialize) configStore.ensure() else configStore.read()
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

private data class MutableTelemetrySettings(
  val level: String,
  val customProxyUrl: String,
  val batchSize: Int,
  val installId: String,
)

private fun configBackedSettings(configPath: java.nio.file.Path, config: Map<String, Any?>?): MutableTelemetrySettings {
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
