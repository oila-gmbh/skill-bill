package skillbill.install

import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import java.nio.file.Files
import java.nio.file.Path

/** Public CLI-facing install operations backed by the internal install primitives. */
object InstallOperations {
  fun planInstall(request: InstallPlanRequest): InstallPlan = buildInstallPlan(request)

  fun applyInstall(plan: InstallPlan): InstallApplyResult = applyInstallPlan(plan)

  fun agentPath(agent: String, home: Path? = null): Path {
    require(agent in SUPPORTED_AGENTS) {
      "Unknown agent '$agent'. Supported agents: ${SUPPORTED_AGENTS.joinToString(", ")}."
    }
    return agentPaths(home).getValue(agent)
  }

  fun detectAgentTargets(home: Path? = null): List<AgentTarget> = detectAgents(home)

  fun codexAgentsPath(home: Path? = null): Path = skillbill.install.codexAgentsPath(home)

  fun claudeAgentsPath(home: Path? = null): Path {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return resolvedHome.resolve(".claude/agents")
  }

  fun opencodeAgentsPath(home: Path? = null): Path = skillbill.install.opencodeAgentsPath(home)

  fun junieAgentsPath(home: Path? = null): Path {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return resolvedHome.resolve(".junie/agents")
  }

  fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path? = null, home: Path? = null): List<Path> {
    val resolvedTargetDir = targetDir.toAbsolutePath().normalize()
    Files.createDirectories(resolvedTargetDir)
    return installSkill(
      skillPath = source,
      agentTargets = listOf(AgentTarget(agent.ifBlank { "manual" }, resolvedTargetDir)),
      context = InstallContext(
        repoRoot = repoRoot?.toAbsolutePath()?.normalize(),
        home = home ?: Path.of(System.getProperty("user.home")),
      ),
    )
  }
}
