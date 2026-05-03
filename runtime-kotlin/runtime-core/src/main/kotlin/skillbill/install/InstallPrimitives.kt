@file:Suppress("LoopWithTooManyJumpStatements")

package skillbill.install

import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallTransaction
import java.nio.file.Files
import java.nio.file.Path

internal val SUPPORTED_AGENTS: List<String> = listOf("copilot", "claude", "codex", "opencode")
internal const val CODEX_AGENTS_KIND: String = "codex-agents"
internal const val OPENCODE_AGENTS_KIND: String = "opencode-agents"

internal fun agentPaths(home: Path? = null): Map<String, Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return mapOf(
    "copilot" to resolvedHome.resolve(".copilot/skills"),
    "claude" to resolvedHome.resolve(".claude/commands"),
    "opencode" to resolvedHome.resolve(".config/opencode/skills"),
    "codex" to codexPath(resolvedHome),
  )
}

internal fun codexAgentsPath(home: Path? = null): Path {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  val codexRoot = resolvedHome.resolve(".codex")
  val codexAgents = codexRoot.resolve("agents")
  return if (Files.exists(codexRoot) || Files.exists(codexAgents)) {
    codexAgents
  } else {
    resolvedHome.resolve(".agents/agents")
  }
}

internal fun opencodeAgentsPath(home: Path? = null): Path {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return resolvedHome.resolve(".config/opencode/agents")
}

internal fun detectAgents(home: Path? = null): List<AgentTarget> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return SUPPORTED_AGENTS.mapNotNull { agent ->
    val path = agentPaths(resolvedHome).getValue(agent)
    if (agentIsPresent(resolvedHome, agent, path)) {
      AgentTarget(agent, path)
    } else {
      null
    }
  }
}

internal fun detectCodexAgentsTarget(home: Path? = null): AgentTarget? {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  val path = codexAgentsPath(resolvedHome)
  return if (agentIsPresent(resolvedHome, "codex", path)) AgentTarget(CODEX_AGENTS_KIND, path) else null
}

internal fun detectOpencodeAgentsTarget(home: Path? = null): AgentTarget? {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  val path = opencodeAgentsPath(resolvedHome)
  return if (agentIsPresent(resolvedHome, "opencode", path)) AgentTarget(OPENCODE_AGENTS_KIND, path) else null
}

internal fun installSkill(
  skillPath: Path,
  agentTargets: Iterable<AgentTarget>,
  transaction: InstallTransaction? = null,
): List<Path> {
  val resolvedSkill = skillPath.toAbsolutePath().normalize()
  if (!Files.isDirectory(resolvedSkill)) {
    throw java.io.FileNotFoundException("Skill directory '$resolvedSkill' does not exist.")
  }
  val created = mutableListOf<Path>()
  for (target in agentTargets) {
    Files.createDirectories(target.path)
    val linkPath = target.path.resolve(resolvedSkill.fileName)
    if (Files.isSymbolicLink(linkPath)) {
      val existingTarget = runCatching { Files.readSymbolicLink(linkPath).toAbsolutePath().normalize() }.getOrNull()
      if (existingTarget == resolvedSkill) {
        continue
      }
      Files.deleteIfExists(linkPath)
    } else if (Files.exists(linkPath)) {
      Files.delete(linkPath)
    }
    Files.createSymbolicLink(linkPath, resolvedSkill)
    created.add(linkPath)
    transaction?.createdSymlinks?.add(linkPath)
  }
  return created
}

internal fun uninstallTargets(createdSymlinks: Iterable<Path>): List<Path> {
  val removed = mutableListOf<Path>()
  for (linkPath in createdSymlinks) {
    if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
      Files.deleteIfExists(linkPath)
      removed.add(linkPath)
    }
  }
  return removed
}

private fun codexPath(home: Path): Path {
  val codexRoot = home.resolve(".codex")
  val codexSkills = codexRoot.resolve("skills")
  return if (Files.exists(codexRoot) || Files.exists(codexSkills)) codexSkills else home.resolve(".agents/skills")
}

private fun agentIsPresent(home: Path, agent: String, installPath: Path): Boolean {
  if (Files.exists(installPath)) {
    return true
  }
  val roots = when (agent) {
    "copilot" -> listOf(home.resolve(".copilot"))
    "claude" -> listOf(home.resolve(".claude"))
    "opencode" -> listOf(home.resolve(".config/opencode"))
    "codex" -> listOf(home.resolve(".codex"), home.resolve(".agents"))
    else -> emptyList()
  }
  return roots.any(Files::exists)
}
