package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.ExternalAddonConfigError
import skillbill.install.model.ExternalAgentAddonSource
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigResult
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileExternalAgentAddonSourceConfigStore : ExternalAgentAddonSourceConfigPort {
  override fun readExternalAgentAddonSources(
    request: ExternalAgentAddonSourceConfigRequest,
  ): ExternalAgentAddonSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    if (!Files.exists(configPath)) return ExternalAgentAddonSourceConfigResult()
    val payload = try {
      readTelemetryConfigFile(configPath)?.payload
    } catch (error: IllegalArgumentException) {
      throw ExternalAddonConfigError(error.message.orEmpty(), error)
    } ?: return ExternalAgentAddonSourceConfigResult()

    val raw = payload[CONFIG_KEY] ?: return ExternalAgentAddonSourceConfigResult()
    if (raw !is List<*>) {
      throw ExternalAddonConfigError(
        "External addon config at '$configPath': '$CONFIG_KEY' must be a list of source entries.",
      )
    }
    val sources = raw.mapIndexedNotNull { index, entry -> parseEntry(configPath, request.userHome, index, entry) }
    return ExternalAgentAddonSourceConfigResult(sources)
  }

  private fun parseEntry(configPath: Path, userHome: Path, index: Int, entry: Any?): ExternalAgentAddonSource? {
    val map = entry as? Map<*, *>
      ?: invalidConfig(configPath, "$CONFIG_KEY[$index]", "must be a mapping")
    val kind = (map["kind"] as? String)?.trim()
    if (kind == null && map.containsKey("platform")) return null
    if (kind == "platform-pack") return null
    if (kind != "agent-addon") {
      invalidConfig(
        configPath,
        "$CONFIG_KEY[$index].kind",
        "must be 'agent-addon' for agent add-on sources",
      )
    }
    val rawPath = (map["path"] as? String)?.takeIf(String::isNotBlank)
      ?: invalidConfig(configPath, "$CONFIG_KEY[$index].path", "must be a non-empty string")
    val resolvedPath = resolveSourcePath(userHome, rawPath)
    if (!Files.isDirectory(resolvedPath)) {
      invalidConfig(
        configPath,
        "$CONFIG_KEY[$index].path",
        "'$rawPath' does not exist or is not a directory",
      )
    }
    return ExternalAgentAddonSource(resolvedPath)
  }

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

  private companion object {
    const val CONFIG_KEY = "external_addon_sources"
  }
}

private fun invalidConfig(configPath: Path, field: String, reason: String): Nothing {
  throw ExternalAddonConfigError("External agent add-on config at '$configPath': '$field' $reason.")
}
