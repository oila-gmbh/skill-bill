package skillbill.launcher.mcp

import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.install.model.InstallAgent
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.support.codexConfigRoots
import skillbill.nativeagent.support.claudeConfigRoots
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
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpJsonConfig.register(agent, perProfilePath, command)
      }
      InstallAgent.CODEX -> codexFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpTomlConfig.register(agent, perProfilePath, command)
      }
      InstallAgent.JUNIE -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.CURSOR -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
    }
  }

  fun unregister(
    agent: String,
    home: Path? = null,
    environment: Map<String, String> = System.getenv(),
  ): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpJsonConfig.unregister(agent, perProfilePath)
      }
      InstallAgent.CODEX -> codexFanOut(agent, resolvedHome, environment) { perProfilePath ->
        McpTomlConfig.unregister(agent, perProfilePath)
      }
      InstallAgent.JUNIE -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.CURSOR -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
    }
  }

  fun configFormatFor(agent: InstallAgent): McpConfigFormat = when (agent) {
    InstallAgent.CODEX -> McpConfigFormat.TOML
    InstallAgent.CLAUDE,
    InstallAgent.JUNIE,
    InstallAgent.CURSOR,
    -> McpConfigFormat.JSON
  }

  fun configPathFor(agent: InstallAgent, home: Path): Path = when (agent) {
    InstallAgent.CLAUDE -> home.resolve(".claude.json")
    InstallAgent.CODEX -> home.resolve(".codex/config.toml")
    InstallAgent.JUNIE -> home.resolve(".junie/mcp/mcp.json")
    InstallAgent.CURSOR -> home.resolve(".cursor/mcp.json")
  }

  private fun claudeProfileConfigPaths(home: Path, environment: Map<String, String>): List<Path> {
    val defaultRoot = home.resolve(".claude").toAbsolutePath().normalize()
    return claudeConfigRoots(home, environment).map { root ->
      if (root == defaultRoot) home.resolve(".claude.json") else root.resolve(".claude.json")
    }
  }

  private fun codexProfileConfigPaths(home: Path, environment: Map<String, String>): List<Path> {
    val roots = codexConfigRoots(home, environment)
    return if (roots.isNotEmpty()) {
      roots.map { root -> root.resolve("config.toml") }
    } else {
      listOf(home.resolve(".codex/config.toml"))
    }
  }

  private fun claudeFanOut(
    agent: String,
    home: Path,
    environment: Map<String, String>,
    mutate: (Path) -> McpMutationResult,
  ): McpMutationResult = profileFanOut(
    agent = agent,
    profilePaths = claudeProfileConfigPaths(home, environment),
    representativePath = home.resolve(".claude.json"),
    failureLabel = "Claude",
    mutate = mutate,
  )

  private fun codexFanOut(
    agent: String,
    home: Path,
    environment: Map<String, String>,
    mutate: (Path) -> McpMutationResult,
  ): McpMutationResult = profileFanOut(
    agent = agent,
    profilePaths = codexProfileConfigPaths(home, environment),
    representativePath = home.resolve(".codex/config.toml"),
    failureLabel = "Codex",
    mutate = mutate,
  )

  private fun profileFanOut(
    agent: String,
    profilePaths: List<Path>,
    representativePath: Path,
    failureLabel: String,
    mutate: (Path) -> McpMutationResult,
  ): McpMutationResult {
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
        "Failed to update $failureLabel MCP config for profile(s): $names",
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

enum class McpConfigFormat {
  JSON,
  TOML,
}
