@file:Suppress("MatchingDeclarationName")

package skillbill.nativeagent.rendering

import skillbill.install.plan.detectCodexAgentsTarget
import skillbill.install.plan.detectOpencodeAgentsTarget
import skillbill.install.support.claudeConfigRoots
import skillbill.nativeagent.composition.NativeAgentSource
import java.nio.file.Files
import java.nio.file.Path

enum class NativeAgentProvider(
  val directoryName: String,
  val extension: String,
) {
  Claude("claude-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, mode = null)
    override fun homeAgentDirs(home: Path): List<Path> = claudeConfigRoots(home).map { it.resolve("agents") }
  },
  Codex("codex-agents", "toml") {
    override fun render(source: NativeAgentSource): String = renderCodexAgentToml(source)
    override fun homeAgentDirs(home: Path): List<Path> =
      listOf(home.resolve(".codex/agents"), home.resolve(".agents/agents"))
  },
  Opencode("opencode-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, mode = "subagent")
    override fun homeAgentDirs(home: Path): List<Path> = listOf(home.resolve(".config/opencode/agents"))
  },
  Junie("junie-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, mode = null)
    override fun homeAgentDirs(home: Path): List<Path> = listOf(home.resolve(".junie/agents"))
  },
  Zcode("zcode-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, mode = null)
    override fun homeAgentDirs(home: Path): List<Path> = listOf(home.resolve(".zcode/agents"))
  },
  ;

  abstract fun render(source: NativeAgentSource): String

  abstract fun homeAgentDirs(home: Path): List<Path>

  fun fileName(logicalName: String): String = "$logicalName.$extension"

  fun activeHomeAgentDirs(home: Path): List<Path> = when (this) {
    Claude -> homeAgentDirs(home)
    Codex -> listOfNotNull(detectCodexAgentsTarget(home)?.path)
    Opencode -> listOfNotNull(detectOpencodeAgentsTarget(home)?.path)
    Junie -> homeAgentDirs(home).takeIf { Files.exists(home.resolve(".junie")) }.orEmpty()
    Zcode -> homeAgentDirs(home).takeIf { Files.exists(home.resolve(".zcode")) }.orEmpty()
  }.map { it.toAbsolutePath().normalize() }

  fun cacheArtifactPath(cacheRoot: Path, logicalName: String): Path =
    cacheRoot.resolve(directoryName).resolve(fileName(logicalName)).toAbsolutePath().normalize()
}

private fun renderCodexAgentToml(agent: NativeAgentSource): String = buildString {
  append("""name = "${tomlBasicString(agent.name)}"""").append('\n')
  append("""description = "${tomlBasicString(agent.description)}"""").append('\n')
  append('\n')
  append("developer_instructions = \"\"\"").append('\n')
  append(tomlMultilineString(agent.body.trimEnd())).append('\n')
  append("\"\"\"").append('\n')
}

private fun renderFrontmatterAgent(agent: NativeAgentSource, mode: String?): String = buildString {
  append("---").append('\n')
  append("name: ${yamlScalar(agent.name)}").append('\n')
  append("description: ${yamlScalar(agent.description)}").append('\n')
  if (mode != null) {
    append("mode: $mode").append('\n')
  }
  append("---").append('\n')
  append('\n')
  append(agent.body.trimEnd()).append('\n')
}

private val YAML_RESERVED_LEADING_CHARS: Set<Char> =
  setOf('-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')
private val YAML_RESERVED_INLINE_CHARS: Set<Char> = setOf('\n', '\r', '\t')

internal val YAML_DOUBLE_QUOTE_ESCAPES: Map<Char, String> = mapOf(
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
