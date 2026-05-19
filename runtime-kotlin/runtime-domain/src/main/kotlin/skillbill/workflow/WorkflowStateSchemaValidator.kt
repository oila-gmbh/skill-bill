@file:Suppress("TooGenericExceptionCaught")

package skillbill.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidWorkflowStateSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.workflow.WorkflowStateSchemaValidator")

/**
 * SKILL-48 Subtask 2a: validates a snapshot-shaped `Map<String, Any?>`
 * against the canonical JSON-Schema document at
 * `orchestration/contracts/workflow-state-schema.yaml`.
 *
 * Wraps `com.networknt:json-schema-validator` behind a thin Kotlin
 * interface so the underlying library choice stays local. The schema is
 * loaded ONCE per validator instance (cached) because schema compilation
 * is non-trivial.
 *
 * Coherence rules (cross-field validation) stay in `WorkflowEngine` and
 * the per-skill `WorkflowDefinition`s; see `x-coherence-checks` in the
 * schema file for the named list.
 */
interface WorkflowStateSchemaValidator {
  /**
   * Validates the snapshot-shaped map against the canonical schema. On
   * any violation, throws [InvalidWorkflowStateSchemaError] whose
   * message names the offending field path so the failure surface stays
   * loud and useful.
   *
   * The typed `Map<String, Any?>` signature (vs a raw `Any?`) makes
   * "is it a mapping?" a compile-time concern of the caller — the
   * `WorkflowEngine` / `WorkflowRecordMapping` parse seams already
   * produce a `LinkedHashMap<String, Any?>` by construction. The `slug`
   * is the snapshot's `workflow_name` (e.g. `bill-feature-implement`)
   * and is woven into the loud-fail message so per-skill regressions
   * are easy to spot.
   */
  fun validate(parsedYaml: Map<String, Any?>, slug: String)
}

/**
 * Default implementation. Resolves the canonical schema from the JVM
 * classpath first (populated at build time from
 * `orchestration/contracts/workflow-state-schema.yaml`); when running
 * from a tree that does not yet bundle the schema as a resource (early
 * bootstrap), it falls back to walking up from the JVM working
 * directory to find the canonical file on disk. The compiled
 * [JsonSchema] is cached across calls.
 */
class CanonicalWorkflowStateSchemaValidator : WorkflowStateSchemaValidator {
  // Lazy singleton: the schema file is parsed and compiled exactly
  // once per validator instance.
  private val schema: JsonSchema by lazy { loadSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun validate(parsedYaml: Map<String, Any?>, slug: String) {
    val instance: JsonNode = mapper.valueToTree(parsedYaml)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    // F-401: emit a structured WARN log BEFORE throwing so a slow-rolling
    // schema-drift incident shows up in dashboards without depending on
    // an unhandled-exception monitor at the read seam. The log line is
    // intentionally bounded (slug + up to two offending field paths +
    // their offending values) — we do NOT log the full snapshot payload
    // because the durable record may carry user content and the loud-
    // fail exception is the authoritative debug surface.
    log.log(Level.WARNING, buildSchemaDriftLog(slug, errors, instance))
    throw InvalidWorkflowStateSchemaError(formatValidationMessage(slug, errors, instance))
  }

  private fun buildSchemaDriftLog(slug: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    val sorted = errors.sortedWith(
      compareBy(
        { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
        { it.instanceLocation?.toString().orEmpty() },
        { it.message.orEmpty() },
      ),
    )
    val topTwo = sorted.take(2)
    val parts = topTwo.map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = dottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractOffendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Workflow state snapshot failed schema validation: slug='$slug' violations=${parts.joinToString(", ")} " +
      "totalViolations=${errors.size}"
  }

  private fun formatValidationMessage(slug: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    // Deterministic ordering by instanceLocation so the loud-fail
    // message is stable across runs (networknt returns a LinkedHashSet
    // but ordering is not part of its public contract). The summary
    // line picks the first error whose path is not the root oneOf
    // aggregate, then we append every other error's `<path>: <detail>`
    // so the message names every offending field. Logging the full
    // error set means tests + humans can debug a violation without
    // re-running the validator, and per-skill `oneOf` failures surface
    // every branch's complaint at once.
    val sorted = errors.sortedWith(
      compareBy(
        { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
        { it.instanceLocation?.toString().orEmpty() },
        { it.message.orEmpty() },
      ),
    )
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = dottedFieldPath(instanceLocation)
    val detail = firstError.message
    val offendingValue = extractOffendingValue(instance, instanceLocation)
    return buildString {
      append("Workflow '")
      append(slug)
      append("': snapshot fails schema validation at '")
      append(fieldPath.ifBlank { "<root>" })
      append("': ")
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      // Surface every secondary error so the typed loud-fail message is
      // self-describing (no need to enable verbose logging to debug).
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = dottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractOffendingValue(instance, otherLocation)
        append(" | ")
        append(otherPath)
        append(": ")
        append(other.message)
        if (otherValue.isNotBlank()) {
          append(" — offending value: ")
          append(otherValue)
        }
      }
    }
  }

  // F-303: delegated to internal pure helpers below so test code can
  // verify offending-value extraction across both networknt reporting
  // formats (JSONPath and JSON-Pointer) without round-tripping through
  // the schema validator.
  private fun extractOffendingValue(instance: JsonNode, instanceLocation: String): String =
    extractOffendingValueFromInstance(instance, instanceLocation)

  // networknt 1.5.x reports `instanceLocation` in JSONPath form like
  // `$.steps.0.status`. JSON-Pointer form (`/steps/0/status`) is also
  // accepted as a fallback for older builds. The resulting dotted form
  // is human-readable and stable across library upgrades.
  private fun dottedFieldPath(instanceLocation: String): String = workflowStateSchemaDottedFieldPath(instanceLocation)
}

internal const val WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE: String =
  WorkflowStateSchemaPaths.CLASSPATH_RESOURCE

internal const val WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH: String =
  WorkflowStateSchemaPaths.REPO_RELATIVE_PATH

/**
 * Loads the canonical schema YAML text from the classpath first;
 * failing that, walks up from the JVM working directory to find the
 * on-disk file. Re-emits as JSON before handing to networknt so the
 * validator gets a predictable JSON tree regardless of how the schema
 * is authored.
 *
 * Mirrors SKILL-47 C7: after compiling the schema, asserts the loaded
 * document's `$id` and `properties.contract_version.const` match the
 * runtime's expected values. This protects against a stale schema
 * being shadowed onto the classpath by a sibling jar.
 */
private fun loadSchema(): JsonSchema {
  try {
    val yamlText = readSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    assertWorkflowStateSchemaIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    // F-403: a misbuilt deploy artifact (missing classpath resource,
    // corrupt YAML, or a shadowed copy) would otherwise silently
    // disable every workflow read/write seam — the validator throws on
    // first use but there is no boot-time signal. Emit a structured
    // ERROR log that names the resolved classpath resource + the
    // underlying error type before re-throwing so the loud-fail signal
    // also reaches dashboards. The throw still propagates so callers
    // see the typed exception.
    log.log(
      Level.SEVERE,
      "Failed to load canonical workflow-state schema: classpath='${WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE}' " +
        "repoRelativePath='${WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH}' errorType='${error::class.qualifiedName}' " +
        "message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

// Visible to tests so they can drive the assertion with synthesized
// YAML nodes without round-tripping through the classpath-loaded schema
// (which is bundled by Gradle and always matches at test time).
// Exposed at module-public visibility because the canonical
// `PlatformPackSchemaCleanupTest` lives in `runtime-core/src/test` and
// the equivalent workflow-state shadow guard reuses the same module's
// test fixtures; `internal` would not cross the module boundary.
fun assertWorkflowStateSchemaIdentity(yamlNode: JsonNode) {
  val loadedId = yamlNode.path("\$id").asText("")
  if (loadedId != WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID) {
    throw InvalidWorkflowStateSchemaError(
      "Canonical workflow-state schema identity mismatch: loaded '\$id' is '$loadedId' but expected " +
        "'${WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the schema is on " +
        "the classpath.",
    )
  }
  val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
  if (loadedConst != WORKFLOW_STATE_CONTRACT_VERSION) {
    throw InvalidWorkflowStateSchemaError(
      "Canonical workflow-state schema contract_version.const mismatch: loaded '$loadedConst' but the " +
        "runtime expects '$WORKFLOW_STATE_CONTRACT_VERSION'. The schema on the classpath is out of date " +
        "relative to the running runtime-domain.",
    )
  }
}

private fun readSchemaText(): String {
  CanonicalWorkflowStateSchemaValidator::class.java.classLoader
    .getResourceAsStream(WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidWorkflowStateSchemaError(
    "Canonical workflow-state schema is missing. Expected to find it on the JVM classpath at " +
      "'$WORKFLOW_STATE_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

/**
 * F-303: visible-to-tests pure helper for offending-value extraction
 * from a networknt `instanceLocation`. Supports both reporting formats
 * networknt has used across versions:
 *
 *  - JSONPath form (`$.steps[0].status` or `$.steps.0.status`)
 *  - JSON-Pointer form (`/steps/0/status`)
 *
 * Pure-integer segments are treated as array indices when (and only
 * when) the current node is a `JsonNode` array. Previously a JSON-
 * Pointer-form error against `/steps/0/status` silently returned `""`
 * because `JsonNode.path("0")` on an array returns `MissingNode`.
 *
 * Exposed at module-public visibility so the canonical violations test
 * (in `runtime-core/src/test`) can drive it directly without forcing
 * networknt into a specific reporting format.
 */
fun extractOffendingValueFromInstance(instance: JsonNode, instanceLocation: String): String {
  val dotted = workflowStateSchemaDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

fun workflowStateSchemaDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

private fun walkForSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(WORKFLOW_STATE_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
