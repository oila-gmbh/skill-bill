@file:Suppress("MatchingDeclarationName")

package skillbill.nativeagent

import java.nio.file.Path

enum class NativeAgentProvider(
  val directoryName: String,
  val extension: String,
) {
  Claude("claude-agents", "md"),
  Codex("codex-agents", "toml"),
  Opencode("opencode-agents", "md"),
  Junie("junie-agents", "md"),
  ;

  fun homeAgentDirs(home: Path): List<Path> = when (this) {
    Claude -> listOf(home.resolve(".claude/agents"))
    Codex -> listOf(home.resolve(".codex/agents"), home.resolve(".agents/agents"))
    Opencode -> listOf(home.resolve(".config/opencode/agents"))
    Junie -> listOf(home.resolve(".junie/agents"))
  }
}

fun renderNativeAgent(agent: NativeAgentSource, provider: NativeAgentProvider): String = when (provider) {
  NativeAgentProvider.Claude -> renderFrontmatterAgent(agent, mode = null)
  NativeAgentProvider.Codex -> renderCodexAgentToml(agent)
  NativeAgentProvider.Opencode -> renderFrontmatterAgent(agent, mode = "subagent")
  NativeAgentProvider.Junie -> renderFrontmatterAgent(agent, mode = null)
}

private fun renderCodexAgentToml(agent: NativeAgentSource): String = buildString {
  appendLine("""name = "${tomlBasicString(agent.name)}"""")
  appendLine("""description = "${tomlBasicString(agent.description)}"""")
  appendLine()
  appendLine("developer_instructions = \"\"\"")
  appendLine(tomlMultilineString(agent.body.trimEnd()))
  appendLine("\"\"\"")
}

private fun renderFrontmatterAgent(agent: NativeAgentSource, mode: String?): String = buildString {
  appendLine("---")
  appendLine("name: ${yamlScalar(agent.name)}")
  appendLine("description: ${yamlScalar(agent.description)}")
  if (mode != null) {
    appendLine("mode: $mode")
  }
  appendLine("---")
  appendLine()
  appendLine(agent.body.trimEnd())
}

private val YAML_RESERVED_LEADING_CHARS: Set<Char> =
  setOf('-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')
private val YAML_RESERVED_INLINE_CHARS: Set<Char> = setOf('\n', '\r', '\t')
private val YAML_DOUBLE_QUOTE_ESCAPES: Map<Char, String> = mapOf(
  '\\' to "\\\\",
  '"' to "\\\"",
  '\n' to "\\n",
  '\r' to "\\r",
  '\t' to "\\t",
)

private fun yamlScalar(value: String): String = if (yamlNeedsQuoting(value)) {
  "\"" + value.map { char -> YAML_DOUBLE_QUOTE_ESCAPES[char] ?: char.toString() }.joinToString("") + "\""
} else {
  value
}

private fun yamlNeedsQuoting(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  val edgeWhitespace = value.first().isWhitespace() || value.last().isWhitespace()
  val leadingReserved = value.first() in YAML_RESERVED_LEADING_CHARS
  val inlineSeparators = value.contains(": ") || value.contains(" #")
  val inlineReserved = value.any { char -> char in YAML_RESERVED_INLINE_CHARS }
  return edgeWhitespace || leadingReserved || inlineSeparators || inlineReserved
}

private fun tomlBasicString(value: String): String = buildString {
  value.forEach { char ->
    when (char) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\b' -> append("\\b")
      '\t' -> append("\\t")
      '\n' -> append("\\n")
      '\u000C' -> append("\\f")
      '\r' -> append("\\r")
      else -> append(char)
    }
  }
}

private fun tomlMultilineString(value: String): String = value.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
