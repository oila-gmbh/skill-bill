package skillbill.launcher

import skillbill.install.model.McpMutationResult
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
