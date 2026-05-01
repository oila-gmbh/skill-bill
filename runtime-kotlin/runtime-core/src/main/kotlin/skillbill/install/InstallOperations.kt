package skillbill.install

import skillbill.install.model.AgentTarget
import java.nio.file.Files
import java.nio.file.Path

/** Public CLI-facing install operations backed by the internal install primitives. */
object InstallOperations {
  fun agentPath(agent: String, home: Path? = null): Path {
    require(agent in SUPPORTED_AGENTS) {
      "Unknown agent '$agent'. Supported agents: ${SUPPORTED_AGENTS.joinToString(", ")}."
    }
    return agentPaths(home).getValue(agent)
  }

  fun detectAgentTargets(home: Path? = null): List<AgentTarget> = detectAgents(home)

  fun linkSkill(source: Path, targetDir: Path, agent: String): List<Path> {
    val resolvedTargetDir = targetDir.toAbsolutePath().normalize()
    Files.createDirectories(resolvedTargetDir)
    return installSkill(
      skillPath = source,
      agentTargets = listOf(AgentTarget(agent.ifBlank { "manual" }, resolvedTargetDir)),
    )
  }
}
