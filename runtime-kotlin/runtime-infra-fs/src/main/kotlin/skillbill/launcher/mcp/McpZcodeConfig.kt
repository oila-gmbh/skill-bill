package skillbill.launcher.mcp

import skillbill.install.model.McpMutationResult
import java.nio.file.Path

internal object McpZcodeConfig {
  fun register(agent: String, path: Path, command: String): McpMutationResult {
    val settings = readJsonObject(path).toMutableMap()
    val mcp = mutableStringAnyMap(settings["mcp"])
    val servers = mutableStringAnyMap(mcp["servers"])
    servers["skill-bill"] = linkedMapOf(
      "type" to "stdio",
      "command" to command,
      "args" to emptyList<String>(),
    )
    mcp["servers"] = servers
    settings["mcp"] = mcp
    writeJson(path, settings)
    return McpMutationResult(agent, path, changed = true)
  }

  fun unregister(agent: String, path: Path): McpMutationResult {
    val settings = readJsonObject(path).toMutableMap()
    val mcp = mutableStringAnyMap(settings["mcp"])
    val servers = mutableStringAnyMap(mcp["servers"])
    val changed = servers.remove("skill-bill") != null
    if (changed) {
      if (servers.isEmpty()) {
        mcp.remove("servers")
      } else {
        mcp["servers"] = servers
      }
      if (mcp.isEmpty()) {
        settings.remove("mcp")
      } else {
        settings["mcp"] = mcp
      }
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path, changed = changed)
  }
}
