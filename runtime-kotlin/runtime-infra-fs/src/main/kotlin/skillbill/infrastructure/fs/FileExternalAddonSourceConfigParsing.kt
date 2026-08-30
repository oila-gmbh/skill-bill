package skillbill.infrastructure.fs

import skillbill.error.ExternalAddonConfigError
import java.nio.file.Files
import java.nio.file.Path

internal fun requireExternalAddonEntryMap(configPath: Path, index: Int, entry: Any?): Map<*, *> =
  entry as? Map<*, *> ?: throw ExternalAddonConfigError(
    "External addon config at '$configPath': 'external_addon_sources[$index]' must be a mapping.",
  )

internal fun validateExternalAddonEntryKind(configPath: Path, index: Int, kind: String?) {
  if (kind != null && kind != "platform-pack") {
    throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].kind' " +
        "must be 'platform-pack' or 'agent-addon'.",
    )
  }
}

internal fun requireExternalAddonEntryPath(configPath: Path, index: Int, map: Map<*, *>): String =
  (map["path"] as? String)?.takeIf(String::isNotBlank)
    ?: throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].path' must be a non-empty string.",
    )

internal fun requireExternalAddonEntryPlatform(configPath: Path, index: Int, map: Map<*, *>): String =
  (map["platform"] as? String)?.takeIf(String::isNotBlank)
    ?: throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].platform' must be a non-empty string.",
    )

internal fun validateExternalAddonEntryDirectory(configPath: Path, index: Int, rawPath: String, resolvedPath: Path) {
  if (!Files.isDirectory(resolvedPath)) {
    throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].path' '$rawPath' " +
        "does not exist or is not a directory.",
    )
  }
}
