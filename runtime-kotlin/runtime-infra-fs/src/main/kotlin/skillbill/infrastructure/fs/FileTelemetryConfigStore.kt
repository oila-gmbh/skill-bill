package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import skillbill.telemetry.defaultLocalTelemetryConfig
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.parseTelemetryBoolValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Inject
class FileTelemetryConfigStore(
  private val context: EnvironmentContext,
) : TelemetryConfigStore {
  private val resolvedContext = context.withProcessDefaults()

  override fun stateDir(): Path = resolveTelemetryStateDir(resolvedContext.environment, resolvedContext.userHome)

  override fun configPath(): Path = resolveTelemetryConfigPath(resolvedContext.environment, resolvedContext.userHome)

  override fun read(): TelemetryConfigDocument? = readTelemetryConfigFile(configPath())

  override fun ensure(): TelemetryConfigDocument = ensureTelemetryConfigFile(configPath(), resolvedContext.environment)

  override fun write(document: TelemetryConfigDocument) = writeTelemetryConfigFile(configPath(), document)

  override fun delete(): Boolean = Files.deleteIfExists(configPath())
}

internal fun resolveTelemetryStateDir(
  environment: Map<String, String> = System.getenv(),
  userHome: Path = Path.of(System.getProperty("user.home")),
): Path = environment[STATE_DIR_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
  expandAndNormalizeTelemetryPath(it, userHome)
} ?: userHome.resolve(".skill-bill").toAbsolutePath().normalize()

internal const val XDG_CONFIG_HOME_KEY = "XDG_CONFIG_HOME"

/**
 * Resolution order (read + write share this so the installer and runtime always agree):
 * 1. [CONFIG_ENVIRONMENT_KEY] explicit override.
 * 2. The durable XDG config path (`$XDG_CONFIG_HOME/skill-bill/config.json`, default `~/.config/...`)
 *    when it already exists. This lives OUTSIDE the wiped `~/.skill-bill/`, so it survives installs.
 * 3. The legacy `~/.skill-bill/config.json` when it exists (backward compatibility for older installs).
 * 4. Otherwise the durable XDG path — so fresh installs write there by default and never get clobbered.
 */
internal fun resolveTelemetryConfigPath(
  environment: Map<String, String> = System.getenv(),
  userHome: Path = Path.of(System.getProperty("user.home")),
): Path {
  environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    return expandAndNormalizeTelemetryPath(it, userHome)
  }
  val xdgBase = environment[XDG_CONFIG_HOME_KEY]?.takeIf(String::isNotBlank)
    ?.let { expandAndNormalizeTelemetryPath(it, userHome) }
    ?: userHome.resolve(".config")
  val xdgConfig = xdgBase.resolve("skill-bill").resolve("config.json").toAbsolutePath().normalize()
  if (Files.exists(xdgConfig)) return xdgConfig
  val legacyConfig = userHome.resolve(".skill-bill").resolve("config.json").toAbsolutePath().normalize()
  if (Files.exists(legacyConfig)) return legacyConfig
  return xdgConfig
}

private fun expandAndNormalizeTelemetryPath(rawPath: String, userHome: Path): Path {
  val normalized =
    when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
  return Path.of(normalized).toAbsolutePath().normalize()
}

internal fun readTelemetryConfigFile(path: Path): TelemetryConfigDocument? {
  if (!Files.exists(path)) {
    return null
  }
  val rawPayload = JsonSupport.parseObjectOrNull(Files.readString(path))
    ?: throw IllegalArgumentException("Telemetry config at '$path' is not valid JSON.")
  val payload = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(rawPayload))
    ?: throw IllegalArgumentException("Telemetry config at '$path' must contain a JSON object.")
  return TelemetryConfigDocument(payload)
}

/**
 * SKILL-52.3: the random install-id seed is an effect, so it is resolved here in infra-fs rather
 * than inside the pure [defaultLocalTelemetryConfig]. We prefer an explicitly injected
 * [INSTALL_ID_ENVIRONMENT_KEY] env value (keeps deterministic test/CI installs stable) and only
 * mint a fresh [UUID] when none is supplied. The minted/injected id is a FALLBACK only:
 * [normalizedInstallId] still prefers an existing persisted `install_id`, so a fresh id is written
 * only on first install.
 */
internal fun ensureTelemetryConfigFile(
  path: Path,
  environment: Map<String, String> = System.getenv(),
): TelemetryConfigDocument {
  path.parent?.let(Files::createDirectories)
  val existing = readTelemetryConfigFile(path)
  val payload = (existing?.payload?.toMutableMap() ?: mutableMapOf())
  val fallbackInstallId =
    environment[INSTALL_ID_ENVIRONMENT_KEY]?.trim()?.takeIf(String::isNotBlank)
      ?: UUID.randomUUID().toString()
  val defaults = defaultLocalTelemetryConfig(fallbackInstallId).payload
  val telemetry = normalizedTelemetryMap(payload, defaults)
  payload["install_id"] = normalizedInstallId(payload, defaults)
  payload["telemetry"] = telemetry
  val document = TelemetryConfigDocument(payload)
  if (!Files.exists(path) || existing != document) {
    writeTelemetryConfigFile(path, document)
  }
  return document
}

internal fun writeTelemetryConfigFile(path: Path, document: TelemetryConfigDocument) {
  path.parent?.let(Files::createDirectories)
  Files.writeString(path, JsonSupport.mapToJsonString(document.payload) + "\n")
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
  is String -> if (parseTelemetryBoolValue(enabledRaw, "telemetry.enabled")) "anonymous" else "off"
  else -> if (enabledRaw == true) "anonymous" else "off"
}
