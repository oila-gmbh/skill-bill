@file:Suppress("TooGenericExceptionCaught")

package skillbill.mcp.telemetry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidTelemetryEventSchemaError
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.mcp.TelemetryEventSchemaValidator")

/**
 * SKILL-48 Subtask 2d: validates a telemetry-event-shaped
 * `Map<String, Any?>` (envelope: `event_name` + `contract_version` +
 * per-event payload fields) against the canonical JSON-Schema document
 * at `orchestration/contracts/telemetry-event-schema.yaml`.
 *
 * Mirrors [skillbill.install.model.InstallPlanSchemaValidator]. The
 * schema is loaded ONCE per process via the [schema] lazy and the
 * compiled [JsonSchema] is cached for every subsequent call. Coherence
 * rules (cross-field validation) stay in `McpToolDispatcher` and
 * surrounding seam code; see `x-coherence-checks` in the schema file
 * for the named list.
 *
 * `validate` throws [InvalidTelemetryEventSchemaError] carrying the
 * dotted `fieldPath` of the first offending value AND the offending
 * `eventName` (nullable: unknown-event-name violations may report a
 * null name) so callers and tests can pinpoint the regression.
 * `assertIdentity` checks the loaded schema's `$id` and
 * `properties.contract_version.const` match the runtime's expected
 * values; downstream JARs shipping a stale classpath copy loud-fail at
 * first validator use.
 */
object TelemetryEventSchemaValidator {
  private val schema: JsonSchema by lazy { loadSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  /**
   * Validates the telemetry-event envelope against the canonical
   * schema. On any violation, throws
   * [InvalidTelemetryEventSchemaError] whose `fieldPath` names the
   * offending field and whose `eventName` carries the envelope's
   * `event_name` (when present) so the failure surface stays loud and
   * useful.
   *
   * Callers normally derive `eventName` from the tool dispatch context
   * (`McpToolRegistry.toolNames`) so violations remain greppable even
   * when the envelope itself omits `event_name`. When the caller knows
   * the event name a-priori, pass it via [eventName]; otherwise pass
   * `null` and the validator best-effort reads it from the envelope.
   */
  fun validate(envelope: Map<String, Any?>, eventName: String? = null) {
    val instance: JsonNode = mapper.valueToTree(envelope)
    val resolvedEventName: String? = eventName ?: (envelope["event_name"] as? String)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    // F-401 (carried over from 2a/2b): emit a structured WARN log
    // BEFORE throwing so a slow-rolling schema-drift incident shows up
    // in dashboards without depending on an unhandled-exception
    // monitor.
    log.log(Level.WARNING, buildSchemaDriftLog(errors, instance, resolvedEventName))
    val sorted = errors.sortedWith(violationOrdering)
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = telemetryEventSchemaDottedFieldPath(instanceLocation)
    val reason = formatValidationReason(sorted, instance)
    throw InvalidTelemetryEventSchemaError(
      fieldPath = fieldPath,
      eventName = resolvedEventName,
      reason = reason,
    )
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
    if (loadedId != TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidTelemetryEventSchemaError(
        fieldPath = "\$id",
        eventName = null,
        reason = "Canonical telemetry-event schema identity mismatch: loaded '\$id' is '$loadedId' but " +
          "expected '${TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the " +
          "schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != TELEMETRY_EVENT_CONTRACT_VERSION) {
      throw InvalidTelemetryEventSchemaError(
        fieldPath = "properties.contract_version.const",
        eventName = null,
        reason = "Canonical telemetry-event schema contract_version.const mismatch: loaded '$loadedConst' " +
          "but the runtime expects '$TELEMETRY_EVENT_CONTRACT_VERSION'. The schema on the classpath is out " +
          "of date relative to the running runtime-mcp.",
      )
    }
  }

  private fun buildSchemaDriftLog(
    errors: Set<ValidationMessage>,
    instance: JsonNode,
    resolvedEventName: String?,
  ): String {
    val sorted = errors.sortedWith(violationOrdering)
    val topTwo = sorted.take(2)
    val parts = topTwo.map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = telemetryEventSchemaDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractOffendingValueFromTelemetryInstance(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Telemetry event '${resolvedEventName ?: "<unknown>"}' failed schema validation: " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val detail = firstError.message
    val offendingValue = extractOffendingValueFromTelemetryInstance(instance, instanceLocation)
    return buildString {
      append(detail)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = telemetryEventSchemaDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractOffendingValueFromTelemetryInstance(instance, otherLocation)
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

internal const val TELEMETRY_EVENT_SCHEMA_CLASSPATH_RESOURCE: String =
  TelemetryEventSchemaPaths.CLASSPATH_RESOURCE

internal const val TELEMETRY_EVENT_SCHEMA_REPO_RELATIVE_PATH: String =
  TelemetryEventSchemaPaths.REPO_RELATIVE_PATH

/**
 * Loads the canonical schema YAML text from the runtime-mcp classpath
 * resource bundled by the `copyTelemetryEventSchema` Gradle task. The
 * MCP adapter is forbidden from touching the local filesystem
 * directly (see `RuntimeArchitectureTest.mcp adapter avoids direct
 * filesystem...`), so the classpath resource is the only legitimate
 * runtime source. Re-emits as JSON before handing to networknt so the
 * validator gets a predictable JSON tree regardless of how the schema
 * is authored.
 */
private fun loadSchema(): JsonSchema {
  try {
    val yamlText = readSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    TelemetryEventSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (typed: InvalidTelemetryEventSchemaError) {
    // F-403 (carried over from 2a/2b): a misbuilt deploy artifact
    // (missing classpath resource, corrupt YAML, or a shadowed copy)
    // would otherwise silently disable every telemetry parse seam —
    // the validator throws on first use but there is no boot-time
    // signal. The typed canonical error is already in the right shape;
    // log it and rethrow without re-wrapping so callers see one stable
    // error class at every telemetry parse seam.
    log.log(
      Level.SEVERE,
      "Failed to load canonical telemetry-event schema: classpath='$TELEMETRY_EVENT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$TELEMETRY_EVENT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${typed::class.qualifiedName}' message='${typed.message.orEmpty()}'",
      typed,
    )
    throw typed
  } catch (error: Throwable) {
    // F-004 (review-run rvw-20260519-162500-a2d4): wrap any non-typed
    // failure (`JsonParseException`, `IOException`, networknt compile
    // errors, etc.) as the canonical `InvalidTelemetryEventSchemaError`
    // so the lazy schema-load path surfaces a single, greppable error
    // class at every telemetry parse seam. Without this, a corrupted /
    // tampered classpath YAML escaped as a raw Jackson/IO exception
    // and bypassed the typed-error catch arms in `McpStdioServer`,
    // killing the stdio loop on the first `tools/call` (root cause of
    // F-001).
    log.log(
      Level.SEVERE,
      "Failed to load canonical telemetry-event schema: classpath='$TELEMETRY_EVENT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$TELEMETRY_EVENT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw InvalidTelemetryEventSchemaError(
      fieldPath = "",
      eventName = null,
      reason = "Canonical telemetry-event schema document failed to load: ${error.message.orEmpty()}",
      cause = error,
    )
  }
}

private fun readSchemaText(): String {
  TelemetryEventSchemaValidator::class.java.classLoader
    .getResourceAsStream(TELEMETRY_EVENT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  // The MCP adapter is forbidden from touching the local filesystem
  // directly (see `RuntimeArchitectureTest.mcp adapter avoids direct
  // filesystem...`). The canonical telemetry-event schema MUST ship on
  // the runtime-mcp classpath via the `copyTelemetryEventSchema` Gradle
  // task. A missing resource here means the deploy artifact was
  // misbuilt; loud-fail at first validator use so the regression
  // surfaces in dashboards (F-403).
  throw InvalidTelemetryEventSchemaError(
    fieldPath = "",
    eventName = null,
    reason = "Canonical telemetry-event schema is missing from the runtime-mcp classpath at " +
      "'$TELEMETRY_EVENT_SCHEMA_CLASSPATH_RESOURCE'. The on-disk source of truth lives at " +
      "'$TELEMETRY_EVENT_SCHEMA_REPO_RELATIVE_PATH' — confirm `copyTelemetryEventSchema` ran during " +
      "`processResources` so the bytes were bundled into the runtime artifact.",
  )
}

/**
 * Visible-to-tests pure helper for offending-value extraction from a
 * networknt `instanceLocation`. Supports both reporting formats
 * networknt has used across versions (JSONPath and JSON-Pointer). See
 * the install-plan validator's analogous helper for the rationale.
 */
fun extractOffendingValueFromTelemetryInstance(instance: JsonNode, instanceLocation: String): String {
  val dotted = telemetryEventSchemaDottedFieldPath(instanceLocation)
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

fun telemetryEventSchemaDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}
