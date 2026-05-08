package skillbill.nativeagent

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

const val NATIVE_AGENT_SOURCE_DIR = "native-agents"
private const val FRONTMATTER_OPEN_LENGTH = 4

data class NativeAgentSource(
  val name: String,
  val description: String,
  val body: String,
  val path: Path? = null,
)

fun discoverNativeAgentSources(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFilesByDir(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = NATIVE_AGENT_SOURCE_DIR,
  extension = "md",
)

fun discoverNativeAgentFilesByDir(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
  directoryName: String,
  extension: String,
): List<Path> {
  val discovered = linkedSetOf<Path>()
  nativeAgentSourceDiscoveryRoots(platformPacksRoot, skillsRoot, selectedPlatforms).forEach { root ->
    if (Files.isDirectory(root)) {
      val allowedRoot = root.toRealPath()
      Files.walk(root).use { stream ->
        stream
          .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
          .filter { file ->
            file.parent?.name == directoryName && file.fileName.toString().endsWith(".$extension")
          }
          .map { file -> file.toRealPath() }
          .filter { file -> file.startsWith(allowedRoot) }
          .forEach(discovered::add)
      }
    }
  }
  return discovered.sortedBy { it.toString() }
}

fun nativeAgentSourceDiscoveryRoots(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
): List<Path> = if (selectedPlatforms == null) {
  listOfNotNull(platformPacksRoot, skillsRoot)
} else {
  listOfNotNull(skillsRoot) +
    selectedPlatforms.flatMap { platform ->
      listOfNotNull(platformPacksRoot.resolve(platform), skillsRoot?.resolve(platform))
    }
}.map { root -> root.toAbsolutePath().normalize() }

fun parseNativeAgentSource(path: Path): NativeAgentSource {
  val text = Files.readString(path)
  val parsed = parseNativeAgentSourceText(text, path.toString())
  val expectedFileName = "${parsed.name}.md"
  require(path.fileName.toString() == expectedFileName) {
    "$path: native agent source filename must match frontmatter name '${parsed.name}'"
  }
  return parsed.copy(path = path)
}

fun parseNativeAgentSourceText(text: String, label: String = "native agent source"): NativeAgentSource {
  val normalized = text.replace("\r\n", "\n")
  require(normalized.startsWith("---\n")) {
    "$label: native agent source must start with YAML frontmatter"
  }
  val end = normalized.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  require(end >= 0) {
    "$label: native agent source frontmatter must close with ---"
  }
  val frontmatter = parseSimpleFrontmatter(normalized.substring(FRONTMATTER_OPEN_LENGTH, end), label)
  val name = frontmatter["name"].orEmpty()
  val description = frontmatter["description"].orEmpty()
  require(name.matches(Regex("^[a-z][a-z0-9-]*$"))) {
    "$label: native agent name must be lowercase kebab-case"
  }
  require(description.isNotBlank()) {
    "$label: native agent description is required"
  }
  val body = normalized.substring(end + "\n---\n".length).removePrefix("\n").trimEnd()
  require(body.isNotBlank()) {
    "$label: native agent body is required"
  }
  return NativeAgentSource(name = name, description = description, body = body)
}

fun renderNativeAgentSource(agent: NativeAgentSource): String = buildString {
  appendLine("---")
  appendLine("name: ${agent.name}")
  appendLine("description: ${agent.description}")
  appendLine("---")
  appendLine()
  appendLine(agent.body.trimEnd())
}

private fun parseSimpleFrontmatter(raw: String, label: String): Map<String, String> {
  val parsed = linkedMapOf<String, String>()
  raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
    val separator = line.indexOf(':')
    require(separator > 0) {
      "$label: native agent frontmatter line must use key: value syntax"
    }
    val key = line.substring(0, separator).trim()
    val value = line.substring(separator + 1).trim().trim('"', '\'')
    require(key in setOf("name", "description")) {
      "$label: unsupported native agent frontmatter key '$key'"
    }
    parsed[key] = value
  }
  return parsed
}
