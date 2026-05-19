@file:Suppress("TooGenericExceptionCaught")

package skillbill.install.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidInstallPlanSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.install.model.InstallPlanSchemaValidator")

/**
 * SKILL-48 Subtask 2b: validates an install-plan-shaped `Map<String, Any?>`
 * against the canonical JSON-Schema document at
 * `orchestration/contracts/install-plan-schema.yaml`.
 *
 * Mirrors [skillbill.workflow.WorkflowStateSchemaValidator]. The schema
 * is loaded ONCE per process via the [schema] lazy and the compiled
 * [JsonSchema] is cached for every subsequent call. Coherence rules
 * (cross-field validation) stay in `InstallPlanBuilder` and surrounding
 * seam code; see `x-coherence-checks` in the schema file for the named
 * list.
 *
 * `validate` throws [InvalidInstallPlanSchemaError] carrying the dotted
 * `fieldPath` of the first offending value so callers and tests can
 * pinpoint the regression. `assertIdentity` checks the loaded schema's
 * `$id` and `properties.contract_version.const` match the runtime's
 * expected values; downstream JARs shipping a stale classpath copy
 * loud-fail at first validator use.
 */
object InstallPlanSchemaValidator {
  private val schema: JsonSchema by lazy { loadSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  /**
   * Validates the install-plan-shaped map against the canonical
   * schema. On any violation, throws [InvalidInstallPlanSchemaError]
   * whose `fieldPath` names the offending field so the failure surface
   * stays loud and useful.
   */
  fun validate(plan: Map<String, Any?>) {
    val instance: JsonNode = mapper.valueToTree(plan)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    // F-401 (carried over from 2a): emit a structured WARN log BEFORE
    // throwing so a slow-rolling schema-drift incident shows up in
    // dashboards without depending on an unhandled-exception monitor.
    log.log(Level.WARNING, buildSchemaDriftLog(errors, instance))
    val sorted = errors.sortedWith(violationOrdering)
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = installPlanSchemaDottedFieldPath(instanceLocation)
    val reason = formatValidationReason(sorted, instance)
    throw InvalidInstallPlanSchemaError(fieldPath = fieldPath, reason = reason)
  }

  /**
   * Asserts the loaded canonical schema document's `$id` and
   * `properties.contract_version.const` match the runtime's expected
   * values. Visible to tests so they can drive the assertion with
   * synthesized YAML nodes; called from the lazy schema load below.
   */
  fun assertIdentity(yamlText: String) {
    val yamlNode = YAMLMapper().readTree(yamlText)
    assertIdentity(yamlNode)
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidInstallPlanSchemaError(
        fieldPath = "\$id",
        reason = "Canonical install-plan schema identity mismatch: loaded '\$id' is '$loadedId' but " +
          "expected '${InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the " +
          "schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != INSTALL_PLAN_CONTRACT_VERSION) {
      throw InvalidInstallPlanSchemaError(
        fieldPath = "properties.contract_version.const",
        reason = "Canonical install-plan schema contract_version.const mismatch: loaded '$loadedConst' " +
          "but the runtime expects '$INSTALL_PLAN_CONTRACT_VERSION'. The schema on the classpath is out " +
          "of date relative to the running runtime-domain.",
      )
    }
  }

  private fun buildSchemaDriftLog(errors: Set<ValidationMessage>, instance: JsonNode): String {
    val sorted = errors.sortedWith(violationOrdering)
    val topTwo = sorted.take(2)
    val parts = topTwo.map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = installPlanSchemaDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractOffendingValueFromInstance(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Install plan failed schema validation: violations=${parts.joinToString(", ")} " +
      "totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val detail = firstError.message
    val offendingValue = extractOffendingValueFromInstance(instance, instanceLocation)
    return buildString {
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = installPlanSchemaDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractOffendingValueFromInstance(instance, otherLocation)
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

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )
}

internal const val INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE: String =
  InstallPlanSchemaPaths.CLASSPATH_RESOURCE

internal const val INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH: String =
  InstallPlanSchemaPaths.REPO_RELATIVE_PATH

/**
 * Loads the canonical schema YAML text from the classpath first;
 * failing that, walks up from the JVM working directory to find the
 * on-disk file. Re-emits as JSON before handing to networknt so the
 * validator gets a predictable JSON tree regardless of how the schema
 * is authored.
 */
private fun loadSchema(): JsonSchema {
  try {
    val yamlText = readSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    InstallPlanSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    // F-403 (carried over from 2a): a misbuilt deploy artifact (missing
    // classpath resource, corrupt YAML, or a shadowed copy) would
    // otherwise silently disable every install parse seam — the
    // validator throws on first use but there is no boot-time signal.
    log.log(
      Level.SEVERE,
      "Failed to load canonical install-plan schema: classpath='$INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readSchemaText(): String {
  InstallPlanSchemaValidator::class.java.classLoader
    .getResourceAsStream(INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidInstallPlanSchemaError(
    fieldPath = "",
    reason = "Canonical install-plan schema is missing. Expected to find it on the JVM classpath at " +
      "'$INSTALL_PLAN_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

/**
 * Visible-to-tests pure helper for offending-value extraction from a
 * networknt `instanceLocation`. Supports both reporting formats
 * networknt has used across versions (JSONPath and JSON-Pointer). See
 * the workflow-state validator's analogous helper for the rationale.
 */
fun extractOffendingValueFromInstance(instance: JsonNode, instanceLocation: String): String {
  val dotted = installPlanSchemaDottedFieldPath(instanceLocation)
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

fun installPlanSchemaDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

private fun walkForSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(INSTALL_PLAN_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
