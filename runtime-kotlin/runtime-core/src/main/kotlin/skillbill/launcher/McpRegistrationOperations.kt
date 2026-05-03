package skillbill.launcher

import skillbill.install.model.McpMutationResult
import java.nio.file.Path

object McpRegistrationOperations {
  fun register(agent: String, runtimeMcpBin: Path, home: Path? = null): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val command = runtimeMcpBin.toAbsolutePath().normalize().toString()
    return when (agent) {
      "claude" -> McpJsonConfig.register(agent, resolvedHome.resolve(".claude.json"), command)
      "copilot" -> McpJsonConfig.register(agent, resolvedHome.resolve(".copilot/mcp-config.json"), command)
      "codex" -> McpTomlConfig.register(agent, resolvedHome.resolve(".codex/config.toml"), command)
      "opencode" -> McpOpenCodeConfig.register(agent, resolvedHome.resolve(".config/opencode/opencode.json"), command)
      "glm" -> McpJsonConfig.register(agent, resolvedHome.resolve(".glm/mcp-config.json"), command)
      else -> throw IllegalArgumentException("Unknown MCP agent '$agent'.")
    }
  }

  fun unregister(agent: String, home: Path? = null): McpMutationResult {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return when (agent) {
      "claude" -> McpJsonConfig.unregister(agent, resolvedHome.resolve(".claude.json"))
      "copilot" -> McpJsonConfig.unregister(agent, resolvedHome.resolve(".copilot/mcp-config.json"))
      "codex" -> McpTomlConfig.unregister(agent, resolvedHome.resolve(".codex/config.toml"))
      "opencode" -> McpOpenCodeConfig.unregister(agent, resolvedHome.resolve(".config/opencode/opencode.json"))
      "glm" -> McpJsonConfig.unregister(agent, resolvedHome.resolve(".glm/mcp-config.json"))
      else -> throw IllegalArgumentException("Unknown MCP agent '$agent'.")
    }
  }
}
