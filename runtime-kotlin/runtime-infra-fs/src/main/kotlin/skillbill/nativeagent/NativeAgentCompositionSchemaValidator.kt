@file:Suppress("TooGenericExceptionCaught")

package skillbill.nativeagent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidNativeAgentCompositionSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.nativeagent.NativeAgentCompositionSchemaValidator")

/**
 * SKILL-48 Subtask 2c: validates a native-agent composition source
 * against the canonical JSON-Schema document at
 * `orchestration/contracts/native-agent-composition-schema.yaml`.
 *
 * Mirrors [skillbill.install.model.InstallPlanSchemaValidator]. The
 * schema is loaded ONCE per process via the [schema] lazy and the
 * compiled [JsonSchema] is cached for every subsequent call. Coherence
 * rules (cross-field validation, filename-vs-name parity) stay in the
 * parse seams in `NativeAgentBundle.kt` / `NativeAgentSource.kt`; see
 * `x-coherence-checks` in the schema file for the named list.
 *
 * `validate` throws [InvalidNativeAgentCompositionSchemaError] carrying
 * the `sourceLabel` and the collected violation messages so callers
 * and tests can pinpoint the regression.
 */
object NativeAgentCompositionSchemaValidator {
  private val schema: JsonSchema by lazy { loadSchema() }
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val jsonMapper: ObjectMapper by lazy { ObjectMapper() }

  /**
   * Validates the raw YAML text of a native-agent source against the
   * canonical schema. On any violation, throws
   * [InvalidNativeAgentCompositionSchemaError] whose message names the
   * `sourceLabel` and the violation list so the failure surface stays
   * loud and useful.
   */
  fun validate(yamlText: String, sourceLabel: String) {
    val instance: JsonNode = try {
      yamlMapper.readTree(yamlText)
    } catch (error: Throwable) {
      throw InvalidNativeAgentCompositionSchemaError(
        sourceLabel = sourceLabel,
        reason = "could not parse YAML for schema validation: ${error.message.orEmpty()}",
        cause = error,
      )
    }
    validateNode(instance, sourceLabel)
  }

  /**
   * Validates a pre-parsed YAML node (e.g. produced by the frontmatter
   * extractor in `NativeAgentSource.kt`). Mirrors
   * [InstallPlanSchemaValidator]'s parsed-node entry point so callers
   * that already hold a parsed map can validate without re-serializing.
   */
  fun validateParsedNode(node: JsonNode, sourceLabel: String) {
    validateNode(node, sourceLabel)
  }

  private fun validateNode(instance: JsonNode, sourceLabel: String) {
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    // F-401 (carried over from 2a/2b): emit a structured WARN log
    // BEFORE throwing so a slow-rolling schema-drift incident shows up
    // in dashboards without depending on an unhandled-exception
    // monitor.
    log.log(Level.WARNING, buildSchemaDriftLog(errors, sourceLabel))
    val sorted = errors.sortedWith(violationOrdering)
    val reason = formatValidationReason(sorted)
    throw InvalidNativeAgentCompositionSchemaError(sourceLabel = sourceLabel, reason = reason)
  }

  /**
   * Asserts the loaded canonical schema document's `$id` and
   * `$defs.contractVersion.const` match the runtime's expected values.
   * Visible to tests so they can drive the assertion with synthesized
   * YAML nodes; called from the lazy schema load below.
   */
  fun assertIdentity(yamlText: String) {
    val yamlNode = yamlMapper.readTree(yamlText)
    assertIdentity(yamlNode)
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidNativeAgentCompositionSchemaError(
        sourceLabel = "\$id",
        reason = "Canonical native-agent composition schema identity mismatch: loaded '\$id' is '$loadedId' " +
          "but expected '${NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy " +
          "of the schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("\$defs").path("contractVersion").path("const").asText("")
    if (loadedConst != NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION) {
      throw InvalidNativeAgentCompositionSchemaError(
        sourceLabel = "\$defs.contractVersion.const",
        reason = "Canonical native-agent composition schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but the runtime expects '$NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION'. The schema " +
          "on the classpath is out of date relative to the running runtime-core.",
      )
    }
  }

  private fun buildSchemaDriftLog(errors: Set<ValidationMessage>, sourceLabel: String): String {
    val sorted = errors.sortedWith(violationOrdering)
    val topTwo = sorted.take(2)
    val parts = topTwo.map { error ->
      val location = error.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
      "$location: ${error.message.orEmpty()}"
    }
    return "Native agent composition source '$sourceLabel' failed schema validation: " +
      "violations=${parts.joinToString(" | ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>): String = buildString {
    val first = sorted.first()
    val firstLocation = first.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
    append(firstLocation).append(": ").append(first.message.orEmpty())
    sorted.drop(1).forEach { other ->
      val otherLocation = other.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
      append(" | ").append(otherLocation).append(": ").append(other.message.orEmpty())
    }
  }

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )

  private fun loadSchema(): JsonSchema {
    try {
      val yamlText = readSchemaText()
      val yamlNode = yamlMapper.readTree(yamlText)
      assertIdentity(yamlNode)
      val jsonText = jsonMapper.writeValueAsString(yamlNode)
      val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      return factory.getSchema(jsonText)
    } catch (error: Throwable) {
      // F-403 (carried over from 2a/2b): a misbuilt deploy artifact
      // (missing classpath resource, corrupt YAML, or a shadowed copy)
      // would otherwise silently disable every native-agent parse
      // seam — the validator throws on first use but there is no
      // boot-time signal.
      log.log(
        Level.SEVERE,
        "Failed to load canonical native-agent composition schema: " +
          "classpath='${NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE}' " +
          "repoRelativePath='${NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH}' " +
          "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
        error,
      )
      throw error
    }
  }

  private fun readSchemaText(): String {
    NativeAgentCompositionSchemaValidator::class.java.classLoader
      .getResourceAsStream(NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE)
      ?.use { return it.readBytes().toString(Charsets.UTF_8) }

    val walkAnchor: Path = Path.of("").toAbsolutePath()
    val resolved = walkForSchemaFile(walkAnchor)
    if (resolved != null) {
      return Files.readString(resolved)
    }
    throw InvalidNativeAgentCompositionSchemaError(
      sourceLabel = "<schema-load>",
      reason = "Canonical native-agent composition schema is missing. Expected to find it on the JVM " +
        "classpath at '${NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE}' or on disk under " +
        "'${NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH}' walked up from: $walkAnchor.",
    )
  }

  private fun walkForSchemaFile(hint: Path): Path? {
    var current: Path? = hint.toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) {
        return candidate
      }
      current = current.parent
    }
    return null
  }
}
