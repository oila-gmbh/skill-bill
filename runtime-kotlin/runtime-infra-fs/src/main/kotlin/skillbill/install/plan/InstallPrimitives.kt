@file:Suppress("LoopWithTooManyJumpStatements")

package skillbill.install.plan

import skillbill.error.InvalidInternalSkillClassificationError
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallTransaction
import skillbill.install.staging.StagedSymlinkTargetInput
import skillbill.install.staging.resolveStagedSymlinkTarget
import skillbill.install.support.claudeConfigRoot
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.claudeSkillTargets
import skillbill.install.support.codexAgentsTargets
import skillbill.install.support.codexConfigRoot
import skillbill.install.support.codexConfigRoots
import skillbill.install.support.codexSkillTargets
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

internal val SUPPORTED_AGENTS: List<String> = InstallAgent.supportedIds
internal const val CODEX_AGENTS_KIND: String = "codex-agents"
internal const val CLAUDE_AGENTS_KIND: String = "claude-agents"
internal const val JUNIE_AGENTS_KIND: String = "junie-agents"
internal const val CURSOR_AGENTS_KIND: String = "cursor-agents"

internal fun agentPaths(home: Path? = null, environment: Map<String, String> = System.getenv()): Map<String, Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return mapOf(
    "copilot" to resolvedHome.resolve(".copilot/skills"),
    "claude" to claudeConfigRoot(resolvedHome, environment).resolve("skills"),
    "junie" to resolvedHome.resolve(".junie/skills"),
    "cursor" to resolvedHome.resolve(".cursor/skills"),
    "codex" to codexConfigRoot(resolvedHome, environment).resolve("skills"),
  )
}

internal fun codexAgentsPath(home: Path? = null, environment: Map<String, String> = System.getenv()): Path {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return codexConfigRoot(resolvedHome, environment).resolve("agents")
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
    } else if (agent == "codex") {
      if (agentIsPresent(resolvedHome, agent, agentPaths(resolvedHome, environment).getValue(agent), environment)) {
        codexSkillTargets(resolvedHome, environment).map { path -> AgentTarget("codex", path) }
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

internal fun detectCodexAgentsTargets(
  home: Path? = null,
  environment: Map<String, String> = System.getenv(),
): List<AgentTarget> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  if (!agentIsPresent(resolvedHome, "codex", agentPaths(resolvedHome, environment).getValue("codex"), environment)) {
    return emptyList()
  }
  return codexAgentsTargets(resolvedHome, environment).map { path -> AgentTarget(CODEX_AGENTS_KIND, path) }
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
  val selectedPackSkills: List<InstallPlanSkill> = emptyList(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
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
  parseInternalForFrontmatter(resolvedSkill.resolve("content.md"))?.let { declaredParent ->
    throw InvalidInternalSkillClassificationError(
      "Skill '${resolvedSkill.fileName}' declares 'internal-for: $declaredParent' and cannot be " +
        "installed or linked directly: internal skills install as '<skill-name>.md' sidecars inside " +
        "their parent's installed directory. Install the parent skill instead.",
    )
  }
  val symlinkTarget = resolveStagedSymlinkTarget(
    StagedSymlinkTargetInput(
      resolvedSkill = resolvedSkill,
      repoRoot = context.repoRoot,
      home = context.home,
      manifests = context.manifests,
      selectedPackSkills = context.selectedPackSkills,
      selectedPlatformSlugs = context.selectedPlatformSlugs,
    ),
  )
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
    "junie" -> listOf(home.resolve(".junie"))
    "cursor" -> listOf(home.resolve(".cursor"))
    "codex" -> {
      val roots = codexConfigRoots(home, environment)
      if (roots.isNotEmpty()) roots else listOf(home.resolve(".codex"), home.resolve(".agents"))
    }
    else -> emptyList()
  }
  return roots.any(Files::exists)
}
