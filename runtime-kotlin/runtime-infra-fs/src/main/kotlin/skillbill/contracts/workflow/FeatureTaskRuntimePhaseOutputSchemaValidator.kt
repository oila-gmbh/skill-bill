@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val featureTaskRuntimePhaseOutputLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator")

/**
 * Schema validator for feature-task-runtime per-phase output payloads, reached
 * by the runtime only through the domain-owned port. Empty `{}`, malformed
 * input, and any schema violation fail with
 * [InvalidFeatureTaskRuntimePhaseOutputSchemaError].
 */
@Suppress("TooManyFunctions")
object FeatureTaskRuntimePhaseOutputSchemaValidator {
  private val schema: JsonSchema by lazy { loadFeatureTaskRuntimePhaseOutputSchema() }
  private val auditRepairSchema: JsonSchema by lazy { loadAuditRepairPlanSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(phaseOutput: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(phaseOutput)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      featureTaskRuntimePhaseOutputLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = formatValidationReason(errors.sortedWith(violationOrdering), instance),
      )
    }
    validateAuditRepairPlan(instance, sourceLabel)
    val phaseId = phaseOutput["phase_id"] as? String
    if (phaseId != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "phase_id must match the executing phase '$sourceLabel' but was '${phaseId.orEmpty()}'.",
      )
    }
  }

  private fun validateAuditRepairPlan(instance: JsonNode, sourceLabel: String) {
    if (sourceLabel != "audit") return
    val producedOutputs = instance.path("produced_outputs")
    val unmetCriteria = producedOutputs.path("unmet_criteria")
    if (!unmetCriteria.isArray || unmetCriteria.isEmpty) return
    val plan = producedOutputs.path("audit_repair_plan")
    try {
      val errors = auditRepairSchema.validate(plan)
      require(errors.isEmpty()) { formatValidationReason(errors.sortedWith(violationOrdering), plan) }
      decodeAuditRepairPlan(plan).requireExactCriterionCoverage(
        unmetCriteria.map {
          it.path("acceptance_criterion_ref").asText()
        },
      )
    } catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs.audit_repair_plan: ${error.reason}",
        cause = error,
      )
    } catch (error: IllegalArgumentException) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs.audit_repair_plan: ${error.message.orEmpty()}",
        cause = error,
      )
    }
  }

  private fun decodeAuditRepairPlan(node: JsonNode): FeatureTaskRuntimeAuditRepairPlan =
    FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = node.path("contract_version").asText(),
      gaps = node.path("gaps").map { gap ->
        FeatureTaskRuntimeAuditGap(
          gapId = gap.path("gap_id").asText(),
          acceptanceCriterionRef = gap.path("acceptance_criterion_ref").asText(),
          acceptanceCriterionText = gap.path("acceptance_criterion_text").asText(),
          failureEvidence = gap.path("failure_evidence").asText(),
          diagnosis = gap.path("diagnosis").asText(),
          affectedBoundary = gap.path("affected_boundary").asText(),
          repairItems = gap.path("repair_items").map { item ->
            FeatureTaskRuntimeRepairItem(
              repairItemId = item.path("repair_item_id").asText(),
              intendedOutcome = item.path("intended_outcome").asText(),
              implementationActions = item.path("implementation_actions").map(JsonNode::asText),
              affectedPathsOrSymbols = item.path("affected_paths_or_symbols").map(JsonNode::asText),
              requiredVerification = item.path("required_verification").map(JsonNode::asText),
              dependsOn = item.path("depends_on").map(JsonNode::asText),
              status = FeatureTaskRuntimeRepairItemStatus.PENDING,
            )
          },
        )
      },
    )

  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
  }

  fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return parsed
  }

  // Agents launched via `claude --print` (and peers) emit a final message, not a bare payload:
  // the JSON object is commonly wrapped in a ``` fence or trailed by a closing remark. Try the
  // most-specific candidate first and fall back to the raw text so a genuinely payload-less or
  // malformed output still surfaces the precise existing error rather than a misleading one.
  private fun readPhaseOutputObjectNode(phaseOutputText: String, sourceLabel: String): JsonNode {
    val parsedCandidates = phaseOutputObjectCandidates(phaseOutputText).mapNotNull(::tryParseObjectNode)
    val validCandidates = parsedCandidates.filter { candidate ->
      schema.validate(candidate).isEmpty() && candidate.path("phase_id").asText("") == sourceLabel
    }.distinctBy { mapper.writeValueAsString(it) }
    if (validCandidates.size > 1) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "Phase output contains multiple conflicting schema-valid envelopes.",
      )
    }
    validCandidates.singleOrNull()?.let { return it }
    parsedCandidates.firstOrNull()?.let { return it }
    return parseObjectNodeStrict(phaseOutputText.trim(), sourceLabel)
  }

  private fun tryParseObjectNode(candidate: String): JsonNode? = try {
    yamlMapper.readTree(candidate)?.takeIf(JsonNode::isObject)
  } catch (error: JsonProcessingException) {
    featureTaskRuntimePhaseOutputLog.log(
      Level.FINE,
      "Phase-output candidate did not parse; trying the next one.",
      error,
    )
    null
  }

  private fun parseObjectNodeStrict(text: String, sourceLabel: String): JsonNode {
    val node =
      try {
        yamlMapper.readTree(text)
      } catch (error: JsonProcessingException) {
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "Phase output is malformed: ${error.originalMessage.orEmpty()}",
          cause = error,
        )
      }
    if (node == null || !node.isObject) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "<root> must be an object.",
      )
    }
    return node
  }

  private fun phaseOutputObjectNodeToMap(node: JsonNode, sourceLabel: String): Map<String, Any?> = try {
    mapper.convertValue(node, mapType)
  } catch (error: IllegalArgumentException) {
    throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
      sourceLabel = sourceLabel,
      reason = "Phase output root object cannot be converted to a string-keyed map: ${error.message.orEmpty()}",
      cause = error,
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema identity mismatch: loaded '\$id' is " +
          "'$loadedId' but expected '${FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or " +
          "shadowed copy of the schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'. The schema on the " +
          "classpath is out of date relative to the running runtime-contracts.",
      )
    }
  }

  private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Feature-task-runtime phase output failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation).ifBlank { "<root>" }
    val offendingValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, instanceLocation)
    return buildString {
      append(fieldPath)
      append(": ")
      append(firstError.message)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = featureTaskRuntimePhaseOutputDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, otherLocation)
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

private fun loadAuditRepairPlanSchema(): JsonSchema = try {
  val resource = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.CLASSPATH_RESOURCE
  val repoPath = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.REPO_RELATIVE_PATH
  val text = FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
    ?: walkForSchemaFile(Path.of("").toAbsolutePath(), repoPath)?.let(Files::readString)
    ?: throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
      sourceLabel = resource,
      reason = "Canonical audit repair plan schema is missing from '$resource' and '$repoPath'.",
    )
  loadAuditRepairPlanSchemaText(text, resource)
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
    sourceLabel = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.CLASSPATH_RESOURCE,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

internal fun loadAuditRepairPlanSchemaText(text: String, sourceLabel: String): JsonSchema = try {
  val node = YAMLMapper().readTree(text)
    ?: throw IllegalArgumentException("Canonical audit repair plan schema is empty.")
  val loadedId = node.path("\$id").asText("")
  val loadedVersion = node.path("properties").path("contract_version").path("const").asText("")
  require(loadedId == FeatureTaskRuntimeAuditRepairPlanSchemaPaths.EXPECTED_SCHEMA_ID) {
    "Canonical audit repair plan schema identity mismatch: loaded '$loadedId' but expected " +
      "'${FeatureTaskRuntimeAuditRepairPlanSchemaPaths.EXPECTED_SCHEMA_ID}'."
  }
  require(loadedVersion == FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION) {
    "Canonical audit repair plan schema contract version mismatch: loaded '$loadedVersion' but expected " +
      "'$FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION'."
  }
  JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(node))
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
    sourceLabel = sourceLabel,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun walkForSchemaFile(hint: Path, repoRelativePath: String): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(repoRelativePath)
    if (Files.isRegularFile(candidate)) return candidate
    current = current.parent
  }
  return null
}

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.REPO_RELATIVE_PATH

private fun loadFeatureTaskRuntimePhaseOutputSchema(): JsonSchema {
  try {
    val yamlText = readFeatureTaskRuntimePhaseOutputSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    FeatureTaskRuntimePhaseOutputSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    featureTaskRuntimePhaseOutputLog.log(
      Level.SEVERE,
      "Failed to load canonical feature-task-runtime phase output schema: " +
        "classpath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readFeatureTaskRuntimePhaseOutputSchemaText(): String {
  FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForFeatureTaskRuntimePhaseOutputSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
      "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private val FENCED_BLOCK = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

// Ordered, de-duplicated candidates to try as the phase-output object: fenced blocks last-first
// (the final fence is the most likely answer), then each balanced top-level `{...}` region last-first
// (recovers the real object when an example object or a brace-bearing prose table precedes it), then a
// first-`{`-to-last-`}` slice as a structural fallback, then the raw text so clean JSON/YAML and genuine
// failures are unchanged.
private fun phaseOutputObjectCandidates(raw: String): List<String> {
  val trimmed = raw.trim()
  return buildList {
    FENCED_BLOCK.findAll(trimmed).map { it.groupValues[1].trim() }.toList().asReversed().forEach(::add)
    balancedTopLevelObjectSpans(trimmed).asReversed().forEach(::add)
    val open = trimmed.indexOf('{')
    val close = trimmed.lastIndexOf('}')
    if (open in 0 until close) {
      add(trimmed.substring(open, close + 1))
    }
    add(trimmed)
  }.filter(String::isNotBlank).distinct()
}

// Each balanced top-level `{...}` region in source order, scanned with JSON string-literal awareness so
// braces inside quoted values never throw off the depth count. The naive first-`{`-to-last-`}` slice
// spans across two disjoint objects (an example then the real answer) or across a prose table peppered
// with braces and parses as neither; isolating each balanced object lets the caller try the final one,
// which is the agent's real answer, before falling back.
private fun balancedTopLevelObjectSpans(text: String): List<String> {
  val scanner = TopLevelObjectScanner(text)
  return text.indices.mapNotNull(scanner::consume)
}

// Single-pass scanner emitting each balanced top-level `{...}` substring as it closes. Splitting the
// string-literal and structural state transitions into their own small steps keeps each branch shallow
// and the whole walk free of loop jumps.
private class TopLevelObjectScanner(private val text: String) {
  private var depth = 0
  private var start = -1
  private var inString = false
  private var escaped = false

  fun consume(index: Int): String? {
    val ch = text[index]
    if (inString) {
      advanceStringState(ch)
      return null
    }
    return advanceStructuralState(ch, index)
  }

  private fun advanceStringState(ch: Char) {
    if (escaped) {
      escaped = false
      return
    }
    when (ch) {
      '\\' -> escaped = true
      '"' -> inString = false
    }
  }

  private fun advanceStructuralState(ch: Char, index: Int): String? {
    when (ch) {
      '"' -> inString = true
      '{' -> openObject(index)
      '}' -> return closeObject(index)
    }
    return null
  }

  private fun openObject(index: Int) {
    if (depth == 0) start = index
    depth += 1
  }

  private fun closeObject(index: Int): String? {
    if (depth == 0) return null
    depth -= 1
    if (depth != 0 || start < 0) return null
    val span = text.substring(start, index + 1)
    start = -1
    return span
  }
}

fun featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

fun extractFeatureTaskRuntimePhaseOutputOffendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation)
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

private fun walkForFeatureTaskRuntimePhaseOutputSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
