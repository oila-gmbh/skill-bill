package skillbill.launcher.mcp

import skillbill.install.model.McpMutationResult
import skillbill.launcher.process.atomicWriteString
import java.nio.file.Files
import java.nio.file.Path

internal object McpTomlConfig {
  fun register(agent: String, path: Path, command: String): McpMutationResult {
    val filtered = removeSkillBillSection(readLines(path))
      .dropLastWhile { it.isBlank() }
      .toMutableList()
    filtered += ""
    filtered += "[mcp_servers.skill-bill]"
    filtered += "command = \"${tomlString(command)}\""
    filtered += "args = []"
    filtered += ""
    writeLines(path, filtered)
    return McpMutationResult(agent, path, changed = true)
  }

  fun unregister(agent: String, path: Path): McpMutationResult {
    val lines = readLines(path)
    val filtered = removeSkillBillSection(lines)
    val changed = filtered != lines
    if (changed) {
      writeLines(path, filtered.dropLastWhile { it.isBlank() } + "")
    }
    return McpMutationResult(agent, path, changed = changed)
  }

  fun writeGovernedServer(
    path: Path,
    serverName: String,
    command: String,
    args: List<String>,
    env: Map<String, String>,
    enabledTools: List<String>,
  ) {
    val lines = buildList {
      add("[mcp_servers.$serverName]")
      add("command = \"${tomlString(command)}\"")
      add("args = [${args.joinToString(", ") { "\"${tomlString(it)}\"" }}]")
      add("enabled_tools = [${enabledTools.joinToString(", ") { "\"${tomlString(it)}\"" }}]")
      add("[mcp_servers.$serverName.env]")
      env.forEach { (key, value) -> add("$key = \"${tomlString(value)}\"") }
    }
    writeLines(path, lines)
  }

  private fun removeSkillBillSection(lines: List<String>): List<String> {
    val filtered = mutableListOf<String>()
    var skipping = false
    var found = false
    lines.forEach { line ->
      if (line.trim() == "[mcp_servers.skill-bill]") {
        skipping = true
        found = true
      } else {
        if (skipping && line.startsWith("[")) {
          skipping = false
        }
        if (!skipping) {
          filtered += line
        }
      }
    }
    return if (found) filtered else lines
  }

  private fun readLines(path: Path): List<String> = if (Files.exists(path)) Files.readAllLines(path) else emptyList()

  private fun writeLines(path: Path, lines: List<String>) {
    atomicWriteString(path, lines.joinToString("\n"))
  }

  private fun tomlString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
