package skillbill.nativeagent.rendering

import skillbill.install.plan.detectCodexAgentsTargets
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.codexAgentsTargets
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.declaresReadOnlyToolset
import java.nio.file.Files
import java.nio.file.Path

enum class NativeAgentProvider(
  val directoryName: String,
  val extension: String,
) {
  Claude("claude-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, toolsetFields(source))
    override fun homeAgentDirs(home: Path): List<Path> = claudeConfigRoots(home).map { it.resolve("agents") }
  },
  Codex("codex-agents", "toml") {
    override fun render(source: NativeAgentSource): String = renderCodexAgentToml(source)
    override fun homeAgentDirs(home: Path): List<Path> = codexAgentsTargets(home)
  },
  Junie("junie-agents", "md") {
    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, toolsetFields(source))
    override fun homeAgentDirs(home: Path): List<Path> = listOf(home.resolve(".junie/agents"))
  },
  Cursor("cursor-agents", "md") {
    override fun render(source: NativeAgentSource): String =
      renderFrontmatterAgent(source, cursorCapabilityFields(source))
    override fun homeAgentDirs(home: Path): List<Path> = listOf(home.resolve(".cursor/agents"))
  },
  ;

  abstract fun render(source: NativeAgentSource): String

  abstract fun homeAgentDirs(home: Path): List<Path>

  fun fileName(logicalName: String): String = "$logicalName.$extension"

  fun activeHomeAgentDirs(home: Path): List<Path> = when (this) {
    Claude -> homeAgentDirs(home)
    Codex -> detectCodexAgentsTargets(home).map { it.path }
    Junie -> homeAgentDirs(home).takeIf { Files.exists(home.resolve(".junie")) }.orEmpty()
    Cursor -> homeAgentDirs(home).takeIf { Files.exists(home.resolve(".cursor")) }.orEmpty()
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

/**
 * The one frontmatter envelope every markdown provider shares. Providers differ only in the
 * capability fields they can express, which they supply already rendered and in emission order, so a
 * change to the envelope itself cannot reach one provider and silently skip another.
 */
private fun renderFrontmatterAgent(agent: NativeAgentSource, capabilityFields: List<String>): String = buildString {
  append("---").append('\n')
  append("name: ${yamlScalar(agent.name)}").append('\n')
  append("description: ${yamlScalar(agent.description)}").append('\n')
  capabilityFields.forEach { field -> append(field).append('\n') }
  append("---").append('\n')
  append('\n')
  append(agent.body.trimEnd()).append('\n')
}

/** Claude and Junie name the permitted tools directly. */
private fun toolsetFields(agent: NativeAgentSource): List<String> = if (agent.tools.isEmpty()) {
  emptyList()
} else {
  listOf("tools: ${agent.tools.joinToString(", ")}")
}

/**
 * Cursor has no `tools` key — its only capability control is a `readonly` boolean — so a declared
 * read-only toolset projects onto that. Rendering neither field would leave the worker on the host
 * default of every tool the parent can reach, restoring the mutation and recursive delegation the
 * declaration exists to remove.
 */
private fun cursorCapabilityFields(agent: NativeAgentSource): List<String> = if (agent.declaresReadOnlyToolset) {
  listOf("readonly: true")
} else {
  emptyList()
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

/**
 * Tokens a YAML parser resolves to a non-string type when they appear as an unquoted plain scalar.
 * The native-agent name pattern permits several of them, so a name of `no` or `off` would install as
 * a boolean and stop matching the file it keys.
 */
private val YAML_PLAIN_RESOLVED_SCALARS: Set<String> =
  setOf("y", "n", "yes", "no", "true", "false", "on", "off", "null", "~")

private fun yamlNeedsQuoting(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  val edgeWhitespace = value.first().isWhitespace() || value.last().isWhitespace()
  val leadingReserved = value.first() in YAML_RESERVED_LEADING_CHARS
  // A colon ending the value is still a value indicator once the line breaks, so an unquoted
  // `description: release notes:` scans as a nested mapping key instead of a string.
  val valueIndicator = value.contains(": ") || value.endsWith(":") || value.contains(" #")
  val inlineReserved = value.any { char -> char in YAML_RESERVED_INLINE_CHARS }
  val plainResolved = value.lowercase() in YAML_PLAIN_RESOLVED_SCALARS || value.toDoubleOrNull() != null
  return edgeWhitespace || leadingReserved || valueIndicator || inlineReserved || plainResolved
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
