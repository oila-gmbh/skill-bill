package skillbill.skillremove

import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.codexAgentsTargets
import java.nio.file.Path

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForSkills(
  request: SkillRemovalRequest,
  cascadedSkillNames: List<String>,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = request.environment.ifEmpty { System.getenv() }
  val out = mutableListOf<AgentSymlinkUnlink>()
  cascadedSkillNames.forEach { name ->
    AgentSymlinkProvider.values().forEach { provider ->
      agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
        val candidate = dir.resolve("$name.md")
        out += AgentSymlinkUnlink(provider = provider, path = candidate.toString().replace('\\', '/'))
      }
    }
  }
  return out
}

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForPlatform(
  request: SkillRemovalRequest,
  platform: String,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = request.environment.ifEmpty { System.getenv() }
  val out = mutableListOf<AgentSymlinkUnlink>()
  AgentSymlinkProvider.values().forEach { provider ->
    agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
      out += AgentSymlinkUnlink(
        provider = provider,
        path = dir.resolve("bill-$platform-*").toString().replace('\\', '/'),
      )
    }
  }
  return out
}

internal fun SkillRemoveJvmFileSystemPlanning.agentHomeDirs(
  provider: AgentSymlinkProvider,
  home: Path,
  environment: Map<String, String>,
): List<Path> = when (provider) {
  AgentSymlinkProvider.CLAUDE -> claudeConfigRoots(home, environment).map { it.resolve("agents") }
  AgentSymlinkProvider.CODEX -> codexAgentsTargets(home, environment)
  AgentSymlinkProvider.JUNIE -> listOf(home.resolve(".junie/agents"))
  AgentSymlinkProvider.CURSOR -> listOf(home.resolve(".cursor/agents"))
}
