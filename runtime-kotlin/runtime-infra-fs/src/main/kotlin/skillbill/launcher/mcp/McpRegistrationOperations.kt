package skillbill.launcher.mcp

import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.install.model.InstallAgent
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.support.claudeConfigRoots
import java.nio.file.Path

object McpRegistrationOperations {
  fun register(
    agent: String,
    runtimeMcpBin: Path,
    home: Path? = null,
    environment: Map<String, String> = System.getenv(),
  ): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val command = runtimeMcpBin.toAbsolutePath().normalize().toString()
    if (agent == "glm") {
      return McpJsonConfig.register(agent, resolvedHome.resolve(".glm/mcp-config.json"), command)
    }
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpJsonConfig.register(agent, perProfilePath, command)
      }
      InstallAgent.COPILOT -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.CODEX -> McpTomlConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.OPENCODE -> McpOpenCodeConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.JUNIE -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.ZCODE -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
    }
  }

  fun unregister(
    agent: String,
    home: Path? = null,
    environment: Map<String, String> = System.getenv(),
  ): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    if (agent == "glm") {
      return McpJsonConfig.unregister(agent, resolvedHome.resolve(".glm/mcp-config.json"))
    }
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpJsonConfig.unregister(agent, perProfilePath)
      }
      InstallAgent.COPILOT -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.CODEX -> McpTomlConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.OPENCODE -> McpOpenCodeConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.JUNIE -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.ZCODE -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
    }
  }

  fun configPathFor(agent: InstallAgent, home: Path): Path = when (agent) {
    InstallAgent.CLAUDE -> home.resolve(".claude.json")
    InstallAgent.COPILOT -> home.resolve(".copilot/mcp-config.json")
    InstallAgent.CODEX -> home.resolve(".codex/config.toml")
    InstallAgent.OPENCODE -> home.resolve(".config/opencode/opencode.json")
    InstallAgent.JUNIE -> home.resolve(".junie/mcp/mcp.json")
    InstallAgent.ZCODE -> home.resolve(".zcode/mcp/mcp.json")
  }

  private fun claudeProfileConfigPaths(home: Path, environment: Map<String, String>): List<Path> {
    val defaultRoot = home.resolve(".claude").toAbsolutePath().normalize()
    return claudeConfigRoots(home, environment).map { root ->
      if (root == defaultRoot) home.resolve(".claude.json") else root.resolve(".claude.json")
    }
  }

  private fun claudeFanOut(
    agent: String,
    home: Path,
    environment: Map<String, String>,
    mutate: (Path) -> McpMutationResult,
  ): McpMutationResult {
    val profilePaths = claudeProfileConfigPaths(home, environment)
    val representativePath = home.resolve(".claude.json")
    val outcomes = mutableListOf<McpProfileOutcome>()
    val failures = mutableListOf<Pair<Path, Throwable>>()

    profilePaths.forEach { perProfilePath ->
      runCatching { mutate(perProfilePath) }
        .onSuccess { result -> outcomes.add(McpProfileOutcome(result.configPath, result.changed)) }
        .onFailure { error -> failures.add(perProfilePath to error) }
    }

    if (failures.isNotEmpty()) {
      val names = failures.joinToString("; ") { (path, error) -> "$path: ${error.message}" }
      throw ClaudeMcpProfileFailure(
        "Failed to update Claude MCP config for profile(s): $names",
        succeeded = outcomes.toList(),
      )
    }

    return McpMutationResult(
      agent = agent,
      configPath = representativePath,
      changed = outcomes.any { it.changed },
      profiles = outcomes,
    )
  }
}
