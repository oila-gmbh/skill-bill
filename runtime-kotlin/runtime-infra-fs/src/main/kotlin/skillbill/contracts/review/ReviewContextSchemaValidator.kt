@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidReviewContextSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private const val MAX_REPORTED_VIOLATIONS: Int = 4

private val reviewContextLog: Logger =
  Logger.getLogger("skillbill.contracts.review.ReviewContextSchemaValidator")

object ReviewContextSchemaValidator {
  private val schemas: ReviewContextSchemas by lazy { loadReviewContextSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(envelope)
    val errors: Set<ValidationMessage> = schemas.forKind(envelope["kind"] as? String).validate(instance)
    if (errors.isNotEmpty()) {
      val sorted = errors.sortedWith(violationOrdering)
      reviewContextLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, sorted, instance))
      throw InvalidReviewContextSchemaError(
        sourceLabel = sourceLabel,
        reason = formatValidationReason(sorted, instance),
      )
    }
  }

  fun validateParentPacket(envelope: Map<String, Any?>, sourceLabel: String) =
    validateKind(envelope, sourceLabel, "parent_packet")

  fun validateAssignment(envelope: Map<String, Any?>, sourceLabel: String) =
    validateKind(envelope, sourceLabel, "assignment")

  fun validateLaunch(envelope: Map<String, Any?>, sourceLabel: String) = validateKind(envelope, sourceLabel, "launch")

  fun assertIdentity(yamlNode: JsonNode) {
    val drift = identityDriftOrNull(yamlNode) ?: return
    throw InvalidReviewContextSchemaError(
      sourceLabel = ReviewContextSchemaPaths.CLASSPATH_RESOURCE,
      reason = drift,
    )
  }

  private fun identityDriftOrNull(yamlNode: JsonNode): String? {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID) {
      return "Canonical review context schema identity mismatch: loaded '\$id' is '$loadedId' but expected " +
        "'${ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy is on the classpath."
    }
    val branches = yamlNode.path("\$defs").fields().asSequence()
      .map { (name, node) -> name to node.path("properties").path("contract_version").path("const").asText("") }
      .filter { (_, const) -> const.isNotBlank() }
      .toList()
    if (branches.isEmpty()) {
      return "Canonical review context schema declares no contract_version const; the classpath copy is " +
        "not a governed review-context contract."
    }
    val drifted = branches.filterNot { (_, const) -> const == REVIEW_CONTEXT_CONTRACT_VERSION }
    if (drifted.isEmpty()) return null
    return "Canonical review context schema contract_version.const mismatch for " +
      "${drifted.map { "${it.first}='${it.second}'" }} but the runtime expects " +
      "'$REVIEW_CONTEXT_CONTRACT_VERSION'. The schema on the classpath is out of date relative to the " +
      "running runtime-contracts."
  }

  private fun validateKind(envelope: Map<String, Any?>, sourceLabel: String, expectedKind: String) {
    val kind = envelope["kind"]
    if (kind != expectedKind) {
      throw InvalidReviewContextSchemaError(
        sourceLabel = sourceLabel,
        reason = "Expected a '$expectedKind' envelope but the payload declares kind='${kind ?: "<missing>"}'.",
      )
    }
    validate(envelope, sourceLabel)
  }
}

private fun buildSchemaDriftLog(sourceLabel: String, sorted: List<ValidationMessage>, instance: JsonNode): String {
  val parts = sorted.take(2).map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = dottedFieldPath(location).ifBlank { "<root>" }
    val offendingValue = offendingValue(instance, location)
    if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
  }
  return "Review context envelope failed schema validation: source='$sourceLabel' " +
    "violations=${parts.joinToString(", ")} totalViolations=${sorted.size}"
}

private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
  val firstError = sorted.first()
  val offendingValue = offendingValue(instance, firstError.instanceLocation?.toString().orEmpty())
  return buildString {
    append(dottedFieldPath(firstError.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
    append(": ")
    append(firstError.message)
    if (offendingValue.isNotBlank()) {
      append(" — offending value: ")
      append(offendingValue)
    }
    sorted.drop(1).take(MAX_REPORTED_VIOLATIONS).forEach { other ->
      append(" | ")
      append(dottedFieldPath(other.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
      append(": ")
      append(other.message)
    }
  }
}

private val violationOrdering: Comparator<ValidationMessage> = compareBy(
  { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
  { it.instanceLocation?.toString().orEmpty() },
  { it.message.orEmpty() },
)

private fun offendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = dottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { segment ->
    if (segment.isBlank()) return@forEach
    node = if (segment.toIntOrNull() != null) node.path(segment.toInt()) else node.path(segment)
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

private fun dottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

internal const val REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE: String =
  ReviewContextSchemaPaths.CLASSPATH_RESOURCE

internal const val REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH: String =
  ReviewContextSchemaPaths.REPO_RELATIVE_PATH

internal class ReviewContextSchemas(private val envelope: JsonSchema, private val branches: Map<String, JsonSchema>) {
  fun forKind(kind: String?): JsonSchema = branches[kind] ?: envelope
}

private fun loadReviewContextSchema(): ReviewContextSchemas {
  try {
    val yamlText = readReviewContextSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    ReviewContextSchemaValidator.assertIdentity(yamlNode)
    val mapper = ObjectMapper()
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    val envelopeSchema = factory.getSchema(mapper.writeValueAsString(yamlNode))
    val defs = yamlNode.path("\$defs")
    val branches = defs.fieldNames().asSequence()
      .filter { defs.path(it).path("properties").path("contract_version").path("const").asText("").isNotBlank() }
      .associateWith { name ->
        val wrapper = mapper.createObjectNode()
        wrapper.put("\$schema", yamlNode.path("\$schema").asText())
        wrapper.put("\$ref", "#/\$defs/" + name)
        wrapper.set<com.fasterxml.jackson.databind.node.ObjectNode>("\$defs", defs.deepCopy())
        factory.getSchema(mapper.writeValueAsString(wrapper))
      }
    return ReviewContextSchemas(envelopeSchema, branches)
  } catch (error: Throwable) {
    reviewContextLog.log(
      Level.SEVERE,
      "Failed to load canonical review context schema: " +
        "classpath='$REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readReviewContextSchemaText(): String {
  ReviewContextSchemaValidator::class.java.classLoader
    .getResourceAsStream(REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForReviewContextSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidReviewContextSchemaError(
    sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical review context schema is missing. Expected to find it on the JVM classpath at " +
      "'$REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private fun walkForReviewContextSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
