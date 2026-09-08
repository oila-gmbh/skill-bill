package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.ExternalAddonConfigError
import skillbill.install.model.ExternalAddonSource
import skillbill.install.support.resolveTelemetryConfigPath
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest
import skillbill.telemetry.model.TelemetryConfigDocument
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileExternalAddonSourceConfigStore : ExternalAddonSourceConfigPort {

  override fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    if (!Files.exists(configPath)) {
      return ExternalAddonSourceConfigResult()
    }
    val payload = try {
      readTelemetryConfigFile(configPath)?.payload
    } catch (error: IllegalArgumentException) {
      throw ExternalAddonConfigError(error.message.orEmpty(), error)
    } ?: return ExternalAddonSourceConfigResult()

    val raw = payload["external_addon_sources"] ?: return ExternalAddonSourceConfigResult()
    if (raw !is List<*>) {
      throw ExternalAddonConfigError(
        "External addon config at '$configPath': 'external_addon_sources' must be a list of {path, platform} entries.",
      )
    }
    val sources = raw.mapIndexedNotNull { index, entry -> parseEntry(configPath, request.userHome, index, entry) }
    return ExternalAddonSourceConfigResult(sources)
  }

  override fun registerExternalAddonSource(
    request: ExternalAddonSourceRegistrationRequest,
  ): ExternalAddonSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    val existing = try {
      readTelemetryConfigFile(configPath)
    } catch (error: IllegalArgumentException) {
      throw ExternalAddonConfigError(error.message.orEmpty(), error)
    }
    val payload = existing?.payload?.toMutableMap() ?: mutableMapOf()
    val rawSources = rawExternalAddonSources(configPath, payload)
    val existingSources = rawSources.mapIndexedNotNull { index, entry ->
      parseEntry(configPath, request.userHome, index, entry)
    }
    val registeredSource = request.source.normalized()
    val alreadyRegistered = existingSources.any { source ->
      source.path == registeredSource.path && source.platform == registeredSource.platform
    }
    val sources = if (alreadyRegistered) {
      existingSources
    } else {
      val updatedRawSources = rawSources +
        mapOf(
          "path" to registeredSource.path.toString(),
          "platform" to registeredSource.platform,
        )
      payload["external_addon_sources"] = updatedRawSources
      writeTelemetryConfigFile(configPath, TelemetryConfigDocument(payload))
      existingSources + registeredSource
    }
    return ExternalAddonSourceConfigResult(sources)
  }

  private fun rawExternalAddonSources(configPath: Path, payload: Map<String, Any?>): List<Any?> {
    val raw = payload["external_addon_sources"] ?: return emptyList()
    if (raw !is List<*>) {
      throw ExternalAddonConfigError(
        "External addon config at '$configPath': 'external_addon_sources' must be a list of {path, platform} entries.",
      )
    }
    return raw
  }

  private fun parseEntry(configPath: Path, userHome: Path, index: Int, entry: Any?): ExternalAddonSource? {
    val map = requireExternalAddonEntryMap(configPath, index, entry)
    val kind = (map["kind"] as? String)?.trim()
    if (kind == "agent-addon") return null
    validateExternalAddonEntryKind(configPath, index, kind)
    val rawPath = requireExternalAddonEntryPath(configPath, index, map)
    val platform = requireExternalAddonEntryPlatform(configPath, index, map)
    val resolvedPath = resolveSourcePath(userHome, rawPath)
    validateExternalAddonEntryDirectory(configPath, index, rawPath, resolvedPath)
    return ExternalAddonSource(path = resolvedPath, platform = platform.trim())
  }

  private fun ExternalAddonSource.normalized(): ExternalAddonSource =
    ExternalAddonSource(path = path.toAbsolutePath().normalize(), platform = platform.trim())

  private fun resolveSourcePath(userHome: Path, rawPath: String): Path {
    val expanded = when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
    val candidate = Path.of(expanded)
    return if (candidate.isAbsolute) {
      candidate.normalize()
    } else {
      Path.of(System.getProperty("user.dir")).resolve(candidate).normalize()
    }
  }
}
