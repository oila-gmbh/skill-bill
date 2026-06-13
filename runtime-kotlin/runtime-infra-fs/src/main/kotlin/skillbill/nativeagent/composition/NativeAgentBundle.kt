package skillbill.nativeagent.composition

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.nativeagent.rendering.YAML_DOUBLE_QUOTE_ESCAPES
import java.nio.file.Files
import java.nio.file.Path

fun parseNativeAgentBundle(path: Path): List<NativeAgentSource> {
  val yamlText = Files.readString(path)
  val raw = try {
    Yaml().load<Any?>(yamlText)
  } catch (error: YAMLException) {
    invalidBundle("$path: native agent bundle is not valid YAML: ${error.message}", error)
  }
  val root = raw as? Map<*, *> ?: invalidBundle("$path: native agent bundle must be a YAML mapping at the top level")
  // SKILL-48 Subtask 2c: allow an optional top-level `contract_version`
  // key (the schema keeps it optional; on-disk fixtures may omit it).
  requireSupportedKeys(root.keys, setOf("agents", "contract_version")) { key ->
    "$path: unsupported native agent bundle key '$key'"
  }
  val agents = root["agents"] as? List<*>
    ?: invalidBundle("$path: native agent bundle field 'agents' must be a list")
  require(agents.isNotEmpty()) {
    "$path: native agent bundle field 'agents' must not be empty"
  }
  val parsed = agents.mapIndexed { index, entry ->
    parseNativeAgentBundleEntry(path, index, entry)
  }
  // SKILL-48 Subtask 2c: validate the raw YAML against the canonical
  // schema as a defense-in-depth backstop AFTER the existing manual
  // `require` checks. The source-level checks preserve their
  // caller-friendly error messages (and their existing test contracts)
  // while the schema layer catches any envelope drift the manual
  // checks miss. The validator loud-fails via
  // `InvalidNativeAgentCompositionSchemaError`.
  NativeAgentCompositionSchemaValidator.validate(yamlText, path.toString())
  return parsed
}

private fun parseNativeAgentBundleEntry(path: Path, index: Int, entry: Any?): NativeAgentSource {
  val entryLabel = "$path agent[$index]"
  val map = entry as? Map<*, *> ?: invalidBundle("$entryLabel: native agent bundle entry must be a mapping")
  requireSupportedKeys(map.keys, setOf("name", "description", "compose", "body")) { key ->
    "$entryLabel: unsupported native agent bundle entry key '$key'"
  }
  val name = map.requiredString("name", entryLabel)
  val label = "$path entry '$name'"
  val description = map.requiredString("description", label)
  val composition = parseCompositionDirective(map.optionalString("compose", label), label)
  val body = map.optionalString("body", label).orEmpty().trimEnd()
  require(name.matches(Regex("^[a-z][a-z0-9-]*$"))) {
    "$label: native agent name must be lowercase kebab-case"
  }
  require(description.isNotBlank()) {
    "$label: native agent description is required"
  }
  require(body.isNotBlank() || composition != null) {
    "$label: native agent body is required"
  }
  return NativeAgentSource(
    name = name,
    description = description,
    body = body,
    composition = composition,
    path = path,
    bundleEntryName = name,
  )
}

fun renderNativeAgentBundle(agents: List<NativeAgentSource>): String = buildString {
  append("contract_version: \"").append(NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION).append('"').append('\n')
  append("agents:").append('\n')
  agents.forEach { agent ->
    append("  - name: ${agent.name}").append('\n')
    append("    description: ${nativeAgentYamlDoubleQuotedScalar(agent.description)}").append('\n')
    agent.composition?.let { directive ->
      append("    compose: ${directive.kind.wireValue}").append('\n')
    }
    val body = agent.body.trimEnd()
    if (body.isNotEmpty()) {
      append("    body: |-").append('\n')
      body.lineSequence().forEach { line ->
        append("      ").append(line).append('\n')
      }
    }
  }
}

private fun requireSupportedKeys(keys: Set<Any?>, supported: Set<String>, message: (Any?) -> String) {
  val unsupported = keys.firstOrNull { it !in supported }
  require(unsupported == null) {
    message(unsupported)
  }
}

private fun Map<*, *>.requiredString(key: String, label: String): String =
  this[key] as? String ?: invalidBundle("$label: native agent bundle entry field '$key' must be a string")

private fun Map<*, *>.optionalString(key: String, label: String): String? {
  val value = this[key] ?: return null
  return value as? String ?: invalidBundle("$label: native agent bundle entry field '$key' must be a string")
}

private fun nativeAgentYamlDoubleQuotedScalar(value: String): String =
  "\"" + value.map { char -> YAML_DOUBLE_QUOTE_ESCAPES[char] ?: char.toString() }.joinToString("") + "\""

private fun invalidBundle(message: String, cause: Throwable? = null): Nothing =
  throw IllegalArgumentException(message, cause)
