package skillbill.nativeagent.composition

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.nativeagent.rendering.YAML_DOUBLE_QUOTE_ESCAPES
import java.nio.file.Files
import java.nio.file.Path

const val NATIVE_AGENT_SOURCE_DIR = "native-agents"
const val NATIVE_AGENT_BUNDLE_FILE = "agents.yaml"
private const val FRONTMATTER_OPEN_LENGTH = 4

data class NativeAgentSource(
  val name: String,
  val description: String,
  val body: String,
  val composition: NativeAgentCompositionDirective? = null,
  val path: Path? = null,
  val bundleEntryName: String? = null,
)

data class NativeAgentCompositionDirective(
  val kind: NativeAgentCompositionKind,
)

enum class NativeAgentCompositionKind(val wireValue: String) {
  GovernedContent("governed-content"),
}

fun parseNativeAgentSource(path: Path): NativeAgentSource {
  val text = Files.readString(path)
  val parsed = parseNativeAgentSourceText(text, path.toString())
  val expectedFileName = "${parsed.name}.md"
  require(path.fileName.toString() == expectedFileName) {
    "$path: native agent source filename must match frontmatter name '${parsed.name}'"
  }
  return parsed.copy(path = path)
}

fun parseNativeAgentSourceFile(path: Path): List<NativeAgentSource> =
  if (path.fileName.toString() == NATIVE_AGENT_BUNDLE_FILE) {
    parseNativeAgentBundle(path)
  } else {
    listOf(parseNativeAgentSource(path))
  }

fun parseNativeAgentSourceText(text: String, label: String = "native agent source"): NativeAgentSource {
  val normalized = text.replace("\r\n", "\n")
  require(normalized.startsWith("---\n")) {
    "$label: native agent source must start with YAML frontmatter"
  }
  val end = normalized.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  require(end >= 0) {
    "$label: native agent source frontmatter must close with ---"
  }
  val frontmatterBlock = normalized.substring(FRONTMATTER_OPEN_LENGTH, end)
  val frontmatter = parseSimpleFrontmatter(frontmatterBlock, label)
  val name = frontmatter["name"].orEmpty()
  val description = frontmatter["description"].orEmpty()
  val composition = parseCompositionDirective(frontmatter["compose"], label)
  require(name.matches(Regex("^[a-z][a-z0-9-]*$"))) {
    "$label: native agent name must be lowercase kebab-case"
  }
  require(description.isNotBlank()) {
    "$label: native agent description is required"
  }
  val body = normalized.substring(end + "\n---\n".length).removePrefix("\n").trimEnd()
  require(body.isNotBlank() || composition != null) {
    "$label: native agent body is required"
  }
  // SKILL-48 Subtask 2c: validate the frontmatter against the
  // canonical schema as a defense-in-depth backstop AFTER the manual
  // require checks. We build a JsonNode directly from the parsed
  // frontmatter values (instead of re-serializing to YAML and feeding
  // it through a generic YAML parser) so descriptions that contain
  // ambiguous YAML punctuation — colons, brackets, leading reserved
  // characters — validate correctly. The schema enforces the
  // body-or-compose anyOf rule on the single-md envelope just like
  // the bundle envelope, while the manual checks preserve their
  // caller-friendly source-level messages.
  val instance: ObjectNode = JsonNodeFactory.instance.objectNode()
  frontmatter["name"]?.let { instance.put("name", it) }
  frontmatter["description"]?.let { instance.put("description", it) }
  frontmatter["compose"]?.let { instance.put("compose", it) }
  frontmatter["contract_version"]?.let { instance.put("contract_version", it) }
  if (body.isNotBlank()) {
    instance.put("body", body)
  }
  NativeAgentCompositionSchemaValidator.validateParsedNode(instance, label)
  return NativeAgentSource(name = name, description = description, body = body, composition = composition)
}

fun renderNativeAgentSource(agent: NativeAgentSource): String = buildString {
  append("---").append('\n')
  append("contract_version: \"").append(NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION).append('"').append('\n')
  append("name: ${agent.name}").append('\n')
  append("description: ${agent.description}").append('\n')
  agent.composition?.let { directive ->
    append("compose: ${directive.kind.wireValue}").append('\n')
  }
  append("---").append('\n')
  append('\n')
  val body = agent.body.trimEnd()
  if (body.isNotEmpty()) {
    append(body).append('\n')
  }
}

private fun parseSimpleFrontmatter(raw: String, label: String): Map<String, String> {
  val parsed = linkedMapOf<String, String>()
  raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
    val separator = line.indexOf(':')
    require(separator > 0) {
      "$label: native agent frontmatter line must use key: value syntax"
    }
    val key = line.substring(0, separator).trim()
    val value = decodeYamlScalar(line.substring(separator + 1).trimStart(), label)
    // SKILL-48 Subtask 2c: allow an optional top-level `contract_version`
    // key (the canonical schema keeps it optional; on-disk fixtures may
    // omit it). Future writes that include the pin must not be rejected
    // by the parser.
    require(key in setOf("name", "description", "compose", "contract_version")) {
      "$label: unsupported native agent frontmatter key '$key'"
    }
    parsed[key] = value
  }
  return parsed
}

// Inverse of YAML_DOUBLE_QUOTE_ESCAPES — derived once so the encode/decode tables stay in sync.
private val DOUBLE_QUOTE_DECODE_MAP: Map<String, String> =
  YAML_DOUBLE_QUOTE_ESCAPES.entries.associate { (decoded, escape) -> escape to decoded.toString() }

private fun decodeYamlScalar(value: String, label: String): String {
  if (value.isEmpty()) {
    return value
  }
  return when (value.first()) {
    '"' -> {
      require(value.length >= 2 && value.endsWith('"')) {
        "$label: native agent frontmatter has unterminated double-quoted scalar"
      }
      val inner = value.substring(1, value.length - 1)
      // If the inner segment ends with an odd number of backslashes the closing quote was actually escaped.
      var trailingBackslashes = 0
      var probe = inner.length - 1
      while (probe >= 0 && inner[probe] == '\\') {
        trailingBackslashes += 1
        probe -= 1
      }
      require(trailingBackslashes % 2 == 0) {
        "$label: native agent frontmatter has unterminated double-quoted scalar"
      }
      decodeYamlDoubleQuoted(inner, label)
    }
    '\'' -> {
      require(value.length >= 2 && value.endsWith('\'')) {
        "$label: native agent frontmatter has unterminated single-quoted scalar"
      }
      decodeYamlSingleQuoted(value.substring(1, value.length - 1), label)
    }
    else -> value.trimEnd()
  }
}

private fun decodeYamlDoubleQuoted(inner: String, label: String): String = buildString {
  var index = 0
  while (index < inner.length) {
    val char = inner[index]
    if (char == '\\') {
      require(index + 1 < inner.length) {
        "$label: native agent frontmatter has unterminated double-quoted scalar"
      }
      val next = inner[index + 1]
      val decoded = DOUBLE_QUOTE_DECODE_MAP["\\$next"]
      require(decoded != null) {
        "$label: native agent frontmatter has unknown escape sequence \\$next"
      }
      append(decoded)
      index += 2
    } else {
      require(char != '"') {
        "$label: native agent frontmatter has unescaped double quote inside double-quoted scalar"
      }
      append(char)
      index += 1
    }
  }
}

private fun decodeYamlSingleQuoted(inner: String, label: String): String = buildString {
  var index = 0
  while (index < inner.length) {
    val char = inner[index]
    if (char == '\'') {
      require(index + 1 < inner.length && inner[index + 1] == '\'') {
        "$label: native agent frontmatter has unescaped single quote inside single-quoted scalar"
      }
      append('\'')
      index += 2
    } else {
      append(char)
      index += 1
    }
  }
}
