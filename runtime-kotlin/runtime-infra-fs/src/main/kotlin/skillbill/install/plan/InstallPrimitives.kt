@file:Suppress("LoopWithTooManyJumpStatements")

package skillbill.install.plan

import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallTransaction
import skillbill.install.staging.resolveStagedSymlinkTarget
import skillbill.install.support.claudeConfigRoot
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.claudeSkillTargets
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

internal val SUPPORTED_AGENTS: List<String> = listOf("copilot", "claude", "codex", "opencode", "junie", "zcode")
internal const val CODEX_AGENTS_KIND: String = "codex-agents"
internal const val CLAUDE_AGENTS_KIND: String = "claude-agents"
internal const val OPENCODE_AGENTS_KIND: String = "opencode-agents"
internal const val JUNIE_AGENTS_KIND: String = "junie-agents"
internal const val ZCODE_AGENTS_KIND: String = "zcode-agents"

internal fun agentPaths(home: Path? = null, environment: Map<String, String> = System.getenv()): Map<String, Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return mapOf(
    "copilot" to resolvedHome.resolve(".copilot/skills"),
    "claude" to claudeConfigRoot(resolvedHome, environment).resolve("skills"),
    "opencode" to resolvedHome.resolve(".config/opencode/skills"),
    "junie" to resolvedHome.resolve(".junie/skills"),
    "zcode" to resolvedHome.resolve(".zcode/skills"),
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

internal fun detectAgents(home: Path? = null, environment: Map<String, String> = System.getenv()): List<AgentTarget> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return SUPPORTED_AGENTS.flatMap { agent ->
    if (agent == "claude") {
      if (agentIsPresent(resolvedHome, agent, agentPaths(resolvedHome, environment).getValue(agent), environment)) {
        claudeSkillTargets(resolvedHome, environment).map { path -> AgentTarget("claude", path) }
      } else {
        emptyList()
      }
    } else {
      val path = agentPaths(resolvedHome, environment).getValue(agent)
      if (agentIsPresent(resolvedHome, agent, path, environment)) {
        listOf(AgentTarget(agent, path))
      } else {
        emptyList()
      }
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

/**
 * Installation context bundling staging-cache inputs so callers can pre-resolve them once and
 * reuse them across a multi-skill install (review F-015). Defaults preserve the legacy behavior
 * of `installSkill` callers that don't care about staging.
 */
internal data class InstallContext(
  val repoRoot: Path? = null,
  val home: Path = Path.of(System.getProperty("user.home")),
  val manifests: List<PlatformManifest>? = null,
)

internal fun installSkill(
  skillPath: Path,
  agentTargets: Iterable<AgentTarget>,
  transaction: InstallTransaction? = null,
  context: InstallContext = InstallContext(),
): List<Path> {
  val resolvedSkill = skillPath.toAbsolutePath().normalize()
  if (!Files.isDirectory(resolvedSkill)) {
    throw java.io.FileNotFoundException("Skill directory '$resolvedSkill' does not exist.")
  }
  // SKILL-40 subtask 2: content-managed skills install via the per-skill staging cache so the
  // source tree stays read-only. Non-content-managed sources (manual `link-skill` against an ad-hoc
  // directory with no content.md) fall back to the legacy direct symlink for backward compat.
  // F-015: callers (e.g. ScaffoldService.performInstall) may pass a pre-discovered manifest list
  // so we don't re-walk platform-packs once per skill in a multi-skill scaffold install.
  val symlinkTarget = resolveStagedSymlinkTarget(resolvedSkill, context.repoRoot, context.home, context.manifests)
  val created = mutableListOf<Path>()
  for (target in agentTargets) {
    Files.createDirectories(target.path)
    val linkPath = target.path.resolve(resolvedSkill.fileName)
    if (Files.isSymbolicLink(linkPath)) {
      val existingTarget = runCatching { Files.readSymbolicLink(linkPath).toAbsolutePath().normalize() }.getOrNull()
      if (existingTarget == symlinkTarget) {
        continue
      }
      Files.deleteIfExists(linkPath)
    } else if (Files.exists(linkPath)) {
      Files.delete(linkPath)
    }
    Files.createSymbolicLink(linkPath, symlinkTarget)
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

private fun agentIsPresent(
  home: Path,
  agent: String,
  installPath: Path,
  environment: Map<String, String> = System.getenv(),
): Boolean {
  if (Files.exists(installPath)) {
    return true
  }
  val roots = when (agent) {
    "copilot" -> listOf(home.resolve(".copilot"))
    "claude" -> claudeConfigRoots(home, environment)
    "opencode" -> listOf(home.resolve(".config/opencode"))
    "junie" -> listOf(home.resolve(".junie"))
    "zcode" -> listOf(home.resolve(".zcode"))
    "codex" -> listOf(home.resolve(".codex"), home.resolve(".agents"))
    else -> emptyList()
  }
  return roots.any(Files::exists)
}
