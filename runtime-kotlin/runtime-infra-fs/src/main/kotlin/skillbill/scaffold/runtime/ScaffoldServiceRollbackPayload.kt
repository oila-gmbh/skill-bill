@file:Suppress("TooGenericExceptionCaught", "MaxLineLength")

package skillbill.scaffold.runtime

import skillbill.error.InvalidScaffoldPayloadError
import java.nio.file.Path

internal fun canonicalName(payload: Map<String, Any?>, defaultName: String): String {
  val provided = payload["name"] as? String
  return when {
    provided.isNullOrBlank() -> defaultName
    provided != defaultName -> throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'name' must be '$defaultName' for this scaffold kind.",
    )
    else -> provided
  }
}

internal fun optionalAddonLocationPath(payload: Map<String, Any?>, repoRoot: Path): Path? {
  if (!payload.containsKey("addon_location_path")) return null
  val rawPath = payload["addon_location_path"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
    )
  if (rawPath.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
    )
  }
  val expanded = when {
    rawPath == "~" -> System.getProperty("user.home")
    rawPath.startsWith("~/") -> Path.of(System.getProperty("user.home"))
      .resolve(rawPath.removePrefix("~/"))
      .toString()
    else -> rawPath
  }
  val candidate = Path.of(expanded)
  return if (candidate.isAbsolute) {
    candidate.normalize()
  } else {
    repoRoot.resolve(candidate).normalize()
  }
}

internal fun displayPath(repoRoot: Path, path: Path): String {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val normalizedPath = path.toAbsolutePath().normalize()
  val display = if (normalizedPath.startsWith(normalizedRoot)) {
    normalizedRoot.relativize(normalizedPath)
  } else {
    normalizedPath
  }
  return display.toString().replace('\\', '/')
}

internal fun defaultPlatformOverrideName(platform: String, family: String): String = if (family == "quality-check") {
  "bill-$platform-code-check"
} else {
  "bill-$platform-$family"
}

internal fun deriveDisplayName(platform: String): String = displayNameFromSlug(platform)

internal fun defaultRepoRoot(): Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

internal fun noAgentsNote(): String =
  "No local AI agents detected; skipping auto-install. Run `./install.sh` to set up " +
    "agent paths when an agent becomes available."
