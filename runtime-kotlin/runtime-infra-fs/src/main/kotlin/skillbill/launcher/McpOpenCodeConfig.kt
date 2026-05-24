package skillbill.launcher

import skillbill.contracts.JsonSupport
import skillbill.install.model.McpMutationResult
import java.nio.file.Files
import java.nio.file.Path

internal object McpOpenCodeConfig {
  fun register(agent: String, path: Path, command: String): McpMutationResult {
    val settings = readJsoncObject(path).toMutableMap()
    val mcp = mutableStringAnyMap(settings["mcp"])
    mcp["skill-bill"] = linkedMapOf(
      "type" to "local",
      "command" to listOf(command),
      "enabled" to true,
    )
    settings["mcp"] = mcp
    writeJson(path, settings)
    return McpMutationResult(agent, path, changed = true)
  }

  fun unregister(agent: String, path: Path): McpMutationResult {
    val settings = readJsoncObject(path).toMutableMap()
    val mcp = mutableStringAnyMap(settings["mcp"])
    val changed = mcp.remove("skill-bill") != null
    if (changed) {
      if (mcp.isEmpty()) {
        settings.remove("mcp")
      } else {
        settings["mcp"] = mcp
      }
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path, changed = changed)
  }

  private fun readJsoncObject(path: Path): Map<String, Any?> {
    val raw = if (Files.exists(path)) Files.readString(path) else ""
    if (raw.isBlank()) return linkedMapOf()
    val stripped = JsoncText.stripTrailingCommas(JsoncText.stripComments(raw))
    return JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(stripped)?.let(JsonSupport::jsonElementToValue))
      ?: throw IllegalArgumentException("Invalid OpenCode JSONC config at '$path'.")
  }
}
