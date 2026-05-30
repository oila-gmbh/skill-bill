package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.install.model.McpMutationResult
import java.nio.file.Path

object McpRegistrationOperations {
  fun register(agent: String, runtimeMcpBin: Path, home: Path? = null): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val command = runtimeMcpBin.toAbsolutePath().normalize().toString()
    if (agent == "glm") {
      return McpJsonConfig.register(agent, resolvedHome.resolve(".glm/mcp-config.json"), command)
    }
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.COPILOT -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.CODEX -> McpTomlConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.OPENCODE -> McpOpenCodeConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
      InstallAgent.JUNIE -> McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command)
    }
  }

  fun unregister(agent: String, home: Path? = null): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    if (agent == "glm") {
      return McpJsonConfig.unregister(agent, resolvedHome.resolve(".glm/mcp-config.json"))
    }
    return when (val installAgent = InstallAgent.fromId(agent)) {
      InstallAgent.CLAUDE -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.COPILOT -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.CODEX -> McpTomlConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.OPENCODE -> McpOpenCodeConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
      InstallAgent.JUNIE -> McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome))
    }
  }

  fun configPathFor(agent: InstallAgent, home: Path): Path = when (agent) {
    InstallAgent.CLAUDE -> home.resolve(".claude.json")
    InstallAgent.COPILOT -> home.resolve(".copilot/mcp-config.json")
    InstallAgent.CODEX -> home.resolve(".codex/config.toml")
    InstallAgent.OPENCODE -> home.resolve(".config/opencode/opencode.json")
    InstallAgent.JUNIE -> home.resolve(".junie/mcp/mcp.json")
  }
}
