package skillbill.scaffold.pointer

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal fun discoverGeneratedAgentAddonPointersUnderSkills(root: Path): List<Path> {
  val skillsRoot = root.resolve("skills")
  if (!skillsRoot.isDirectory()) return emptyList()
  return Files.list(skillsRoot).use { skillDirs ->
    skillDirs
      .filter { skillDir -> Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS) }
      .flatMap { skillDir -> Files.list(skillDir) }
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.name.startsWith("agent-addon-") && path.name.endsWith(".md") }
      .sorted()
      .toList()
  }
}

internal fun discoverAgentAddonGeneratedArtifacts(root: Path): List<Path> {
  val addonsRoot = root.resolve("agent-addons")
  if (!addonsRoot.isDirectory()) return emptyList()
  val generatedNames = setOf(
    "SKILL.md",
    "shell-ceremony.md",
    "telemetry-contract.md",
    "stack-routing.md",
    "claude-agents",
    "codex-agents",
    "opencode-agents",
    "junie-agents",
  )
  return Files.walk(addonsRoot).use { stream ->
    stream.filter { it.name in generatedNames }.sorted().toList()
  }
}
