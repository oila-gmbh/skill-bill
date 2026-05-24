package skillbill.launcher

import skillbill.contracts.JsonSupport
import skillbill.install.model.McpMutationResult
import java.nio.file.Files
import java.nio.file.Path

internal object McpJsonConfig {
  fun register(agent: String, path: Path, command: String): McpMutationResult {
    val settings = readJsonObject(path).toMutableMap()
    val servers = mutableStringAnyMap(settings["mcpServers"])
    servers["skill-bill"] = linkedMapOf(
      "type" to "stdio",
      "command" to command,
      "args" to emptyList<String>(),
    )
    settings["mcpServers"] = servers
    writeJson(path, settings)
    return McpMutationResult(agent, path, changed = true)
  }

  fun unregister(agent: String, path: Path): McpMutationResult {
    val settings = readJsonObject(path).toMutableMap()
    val servers = mutableStringAnyMap(settings["mcpServers"])
    val changed = servers.remove("skill-bill") != null
    if (changed) {
      if (servers.isEmpty()) {
        settings.remove("mcpServers")
      } else {
        settings["mcpServers"] = servers
      }
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path, changed = changed)
  }
}

internal fun readJsonObject(path: Path): Map<String, Any?> {
  val raw = if (Files.exists(path)) Files.readString(path) else ""
  return if (raw.isBlank()) {
    linkedMapOf()
  } else {
    JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(raw)?.let(JsonSupport::jsonElementToValue))
      ?: throw IllegalArgumentException("Invalid JSON config at '$path'.")
  }
}

internal fun writeJson(path: Path, settings: Map<String, Any?>) {
  atomicWriteString(path, JsonSupport.mapToJsonString(settings) + "\n")
}

internal fun mutableStringAnyMap(value: Any?): MutableMap<String, Any?> =
  JsonSupport.anyToStringAnyMap(value)?.toMutableMap() ?: linkedMapOf()
